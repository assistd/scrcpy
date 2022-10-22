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
#include "util/str.h"

extern sc_socket
connect_to_server(struct sc_server *server, unsigned attempts, sc_tick delay,
                  uint32_t host, uint16_t port);


static bool
ios_device_read_info(struct sc_intr *intr, sc_socket device_socket,
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

static bool
ios_sc_server_connect_to(struct sc_server *server, struct sc_server_info *info) {
    sc_socket video_socket = SC_SOCKET_NONE;
    sc_socket control_socket = SC_SOCKET_NONE;
    const struct sc_server_params *params = &server->params;
    bool control = server->params.control;

    uint32_t tunnel_host = params->tunnel_host;
    if (!tunnel_host) {
        tunnel_host = IPV4_LOCALHOST;
    }

    uint16_t tunnel_port = params->tunnel_port;
    if (!tunnel_port) {
        tunnel_port = 21344;
    }
    unsigned attempts = 100;
    sc_tick delay = SC_TICK_FROM_MS(100);
    video_socket = connect_to_server(server, attempts, delay, tunnel_host,
                                        tunnel_port);
    if (video_socket == SC_SOCKET_NONE) {
        goto fail;
    }

    tunnel_port = 21343;
    if (control) {
        // we know that the device is listening, we don't need several
        // attempts
        control_socket = net_socket();
        if (control_socket == SC_SOCKET_NONE) {
            goto fail;
        }
        bool ok = net_connect_intr(&server->intr, control_socket,
                                    tunnel_host, tunnel_port);
        if (!ok) {
            goto fail;
        }
    }

    // The sockets will be closed on stop if device_read_info() fails
    bool ok = ios_device_read_info(&server->intr, video_socket, info);
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
ios_run_server(void *data) {
    struct sc_server *server = data;

    const struct sc_server_params *params = &server->params;
    bool ok = ios_sc_server_connect_to(server, &server->info);
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
ios_sc_server_start(struct sc_server *server) {
    bool ok =
        sc_thread_create(&server->thread, ios_run_server, "ios-video", server);
    if (!ok) {
        LOGE("Could not create ios-video thread");
        return false;
    }

    return true;
}