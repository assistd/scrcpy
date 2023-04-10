#include "server.h"

#include <assert.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <SDL2/SDL_timer.h>
#include <SDL2/SDL_platform.h>

#include "util/file.h"
#include "util/log.h"
#include "util/net_intr.h"
#include "util/process_intr.h"
#include "util/buffer_util.h"
#include "util/str.h"

static bool
udt_device_read_info(struct sc_intr *intr, sc_socket device_socket,
                 struct sc_server_info *info) {
    unsigned char buf[SC_DEVICE_NAME_FIELD_LENGTH + 4];
    ssize_t r = net_recv_all_intr(intr, device_socket, buf, sizeof(buf));
    if (r < SC_DEVICE_NAME_FIELD_LENGTH + 4) {
        LOGE("Could not retrieve device information");
        return false;
    }
    // in case the client sends garbage
    buf[SC_DEVICE_NAME_FIELD_LENGTH - 1] = '\0';
    memcpy(info->device_name, (char *) buf, sizeof(info->device_name));

    /*
    info->frame_size.width = (buf[SC_DEVICE_NAME_FIELD_LENGTH] << 8)
                           | buf[SC_DEVICE_NAME_FIELD_LENGTH + 1];
    info->frame_size.height = (buf[SC_DEVICE_NAME_FIELD_LENGTH + 2] << 8)
                            | buf[SC_DEVICE_NAME_FIELD_LENGTH + 3];
    */
    info->frame_size.width = 400;
    info->frame_size.height = 800;
    return true;
}

enum udt_conn_type {
    UDT_CONN_VIDEO,
    UDT_CONN_CTRL,
};

static sc_socket
connect_to_server(struct sc_server *server, enum udt_conn_type conn_type, uint32_t _host, uint16_t _port) {
    sc_socket _socket = net_socket();
    if (_socket == SC_SOCKET_NONE) {
        return _socket;
    }

    bool ok = net_connect_intr(&server->intr, _socket, _host, _port);
    if (!ok) {
        net_close(_socket);
        LOGW("Could not connect to %u:%d", _host, _port);
        return SC_SOCKET_NONE;
    }

// enable with assistd
#if 1
    // <length: 4-byte> <json string>
    char buf[128];
    int _len = snprintf(&buf[4], sizeof(buf)-4, "{\"serial\":\"%s\",\"conn\":\"%s\"}",
        server->params.req_serial,
        conn_type == UDT_CONN_VIDEO ? "video" : "ctrl");
    assert(_len < sizeof(buf));
    sc_write32be((uint8_t*)buf, _len);

    if (net_send_all(_socket, &buf, 4+_len) != (4+_len)) {
        net_close(_socket);
        return SC_SOCKET_NONE;
    }
#endif

    return _socket;
}

static bool
udt_sc_server_connect_to(struct sc_server *server, struct sc_server_info *info) {
    sc_socket video_socket = SC_SOCKET_NONE;
    sc_socket control_socket = SC_SOCKET_NONE;

    const struct sc_server_params *params = &server->params;
    uint16_t port = params->udt_sa_port;

    // 1. make a video connection
    video_socket = connect_to_server(server, UDT_CONN_VIDEO, IPV4_LOCALHOST, port != 0 ? port : 21344);
    if (video_socket == SC_SOCKET_NONE) {
        goto fail;
    }

    // read one byte to compatible with android-scrcpy
    char byte;
    if (net_recv_intr(&server->intr, video_socket, &byte, 1) != 1) {
        goto fail;
    }

    // 2. make a control connection
    control_socket = connect_to_server(server, UDT_CONN_CTRL, IPV4_LOCALHOST, port != 0 ? port : 21343);
    if (control_socket == SC_SOCKET_NONE) {
        goto fail;
    }

    // The sockets will be closed on stop if device_read_info() fails
    bool ok = udt_device_read_info(&server->intr, video_socket, info);
    if (!ok) {
        goto fail;
    }

    assert(video_socket != SC_SOCKET_NONE);
    server->video_socket = video_socket;
    server->control_socket = control_socket;

   return true;

fail:
    if (video_socket != SC_SOCKET_NONE) {
        if (!net_close(video_socket)) {
            LOGW("Could not close video socket");
        }
    }

    if (control_socket != SC_SOCKET_NONE) {
        if (!net_close(control_socket)) {
            LOGW("Could not close control socket");
        }
    }

    return false;
}

static int
udt_run_server(void *data) {
    struct sc_server *server = data;

    // const struct sc_server_params *params = &server->params;
    bool ok = udt_sc_server_connect_to(server, &server->info);
    // The tunnel is always closed by server_connect_to()
    if (!ok) {
        goto error_connection_failed;
    }

    // Now connected
    server->cbs->on_connected(server, server->cbs_userdata);

    // Wait for server_stop()
    sc_mutex_lock(&server->mutex);
    while (!server->stopped) {
        sc_cond_wait(&server->cond_stopped, &server->mutex);
    }
    sc_mutex_unlock(&server->mutex);

    // Interrupt sockets to wake up socket blocking calls on the server
    assert(server->video_socket != SC_SOCKET_NONE);
    net_interrupt(server->video_socket);

    if (server->control_socket != SC_SOCKET_NONE) {
        // There is no control_socket if --no-control is set
        net_interrupt(server->control_socket);
    }

    return 0;

error_connection_failed:
    server->cbs->on_connection_failed(server, server->cbs_userdata);
    return -1;
}

bool
udt_sc_server_start(struct sc_server *server) {
    bool ok =
        sc_thread_create(&server->thread, udt_run_server, "ios-video", server);
    if (!ok) {
        LOGE("Could not create ios-video thread");
        return false;
    }

    return true;
}