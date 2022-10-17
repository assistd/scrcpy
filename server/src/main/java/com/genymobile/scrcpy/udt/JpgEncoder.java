package com.genymobile.scrcpy.udt;

import android.graphics.PixelFormat;
import android.media.Image;

import com.genymobile.scrcpy.Ln;

import org.libjpegturbo.turbojpeg.TJ;
import org.libjpegturbo.turbojpeg.TJCompressor;
import org.libjpegturbo.turbojpeg.TJException;

import java.nio.ByteBuffer;

public final class JpgEncoder {
    public static final String LIB_PATH = TJ.LIB_JPEG_TURBO_DIR_ENV;

    static {
        if (System.getProperty(JpgEncoder.LIB_PATH) == null) {
            System.setProperty(JpgEncoder.LIB_PATH, "/data/local/tmp/");
        }
    }

    public static final class JpgData {
        public byte[] data;
        public int size;

        public JpgData(byte[] encodedData, int compressedSize) {
            data = encodedData;
            size = compressedSize;
        }

        public JpgData copy() {
            JpgData jpgData = new JpgData(null, 0);
            jpgData.data = new byte[size];
            System.arraycopy(data, 0, jpgData.data, 0, size);
            return jpgData;
        }
    }

    private int maxHeight;
    private int maxWidth;

    private final int subsampling = 2;
    private byte[] encodedData = new byte[0];

    private TJCompressor compressor;

    private int convertFormat(int format) {
        if (format == PixelFormat.RGBA_8888) {
            return TJ.PF_RGBA;
        }
        if (format != PixelFormat.RGBX_8888) {
            return format != PixelFormat.RGB_888 ? -1 : TJ.PF_BGR;
        }
        return TJ.PF_RGBX;
    }

    public final void allocate(int maxWidth, int maxHeight) throws TJException {
        encodedData = new byte[TJ.bufSize(maxWidth, maxHeight, subsampling)];
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        compressor = new TJCompressor();
        compressor.setSubsamp(subsampling);
    }

    public final JpgData encode(Image image, int quality) throws TJException {
        Image.Plane imagePlane = image.getPlanes()[0];
        int rowStride = imagePlane.getRowStride();
        ByteBuffer buffer = imagePlane.getBuffer();
        byte[] bArr = new byte[buffer.remaining()];
        buffer.get(bArr);
        int convertFormat = convertFormat(image.getFormat());
        UdtLn.d("Got source image for jpeg encoding: planes: " + image.getPlanes().length
                + " pitch: " + rowStride
                + " width: " + image.getWidth() + " height: " + image.getHeight()
                + " format: " + image.getFormat());
        TJCompressor tJCompressor = compressor;
        if (tJCompressor != null) {
            tJCompressor.setJPEGQuality(quality);
            tJCompressor.setSourceImage(bArr, 0, 0, image.getWidth(), rowStride, image.getHeight(), convertFormat);
            tJCompressor.compress(encodedData, 3072);
            return new JpgData(encodedData, tJCompressor.getCompressedSize());
        }
        return null;
    }

    public final void free() throws TJException {
        encodedData = new byte[0];
        maxWidth = 0;
        maxHeight = 0;
        if (compressor != null) {
            compressor.close();
        }
        compressor = null;
    }
}
