package mino.moa.note;


import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 简易的 PNG 读写工具
 * <p>
 * <p> 使用 fromPNG 读取 PNG 数据
 * <p> 使用 toPNG 将图片输出为 PNG 数据
 * <p> 使用 fromIMAGE 读取内存图片数据（可以是 GRAY RGB RGBA 格式的图片）
 * <p> 使用 toIMAGE 将图片输出为内存图片数据
 * <p>
 * <p> 附带放缩工具 scaleWidth scaleHeight
 * <p> 如果参数是零或者负数，放缩工具不会生效
 * <p> 放缩会操作内部数据导致图片数据改变
 * <p> 使用 cloneAndScaleWidth cloneAndScaleHeight 可以将改动转移到新图片上
 * <p>
 * <p> 附带粘贴工具 paste 将作为参数的图片贴在原图上
 * <p> 粘贴会操作内部数据导致图片数据改变
 * <p> 使用 cloneAndPaste 可以将改动转移到新图片上
 */
public class SimplePNG implements Cloneable {

    /* PNG Color Type */
    private static final byte TYPE_GRAY = 0;
    private static final byte TYPE_RGB = 2;
    private static final byte TYPE_PALETTE = 3;
    private static final byte TYPE_GRAY_ALPHA = 4;
    private static final byte TYPE_RGB_ALPHA = 6;

    /* PNG Color Planes By Type */
    private static final byte[] COLOR_PLANES = {1, -1, 3, 1, 2, -1, 4};

    /* When Load Failed */
    private static final byte[] DEFAULT_IMAGE = {
            120, -38, -19, -39, 81, 10, -64, 32, 12, -125, 97, -17, 127, -23, 121, 5,
            -57, 76, -37, 100, 127, -64, 55, -95, -97, 69, 16, 117, 45, 66, -38, -14,
            20, 14, -4, 25, 126, 117, 79, -44, 117, -15, -41, -41, -19, 50, -29, -9,
            -11, 79, 48, -29, -9, -14, 127, 89, 99, -27, 89, -113, -33, -41, -81, -24,
            3, -2, 108, -65, -53, -7, -117, 63, -37, -81, 54, -32, -9, -11, -85, -25,
            -32, -49, -10, 79, -18, 33, 126, -4, -8, 125, -3, 39, 119, 70, -11, -35,
            19, 127, -122, 63, -23, -3, 10, -1, 93, -1, -37, 125, 62, -7, -1, 14,
            -1, -4, -1, 95, -14, -13, 108, -55, 70, 115, -87
    };

    /**
     * <pre>
     * ADAM 1   ADAM 3    ADAM 5
     * 0        0 1       0 3 1 3
     *          2 2       4 4 4 4
     *                    2 3 2 3
     *                    4 4 4 4
     * </pre>
     */
    private static final byte[][] ADAM7 = {
            {0, 5, 3, 5, 1, 5, 3, 5},
            {6, 6, 6, 6, 6, 6, 6, 6},
            {4, 5, 4, 5, 4, 5, 4, 5},
            {6, 6, 6, 6, 6, 6, 6, 6},
            {2, 5, 3, 5, 2, 5, 3, 5},
            {6, 6, 6, 6, 6, 6, 6, 6},
            {4, 5, 4, 5, 4, 5, 4, 5},
            {6, 6, 6, 6, 6, 6, 6, 6}
    };

    /* 将 PNG 块的标记转换为文本格式 */
    private static String getChunkName(int name) {
        return String.format("%c%c%c%c",
                name >> 24, (name >> 16) & 0xff, (name >> 8) & 0xff, name & 0xff);
    }

    private static final byte[] signature = {-119, 'P', 'N', 'G', 13, 10, 26, 10};

    /* 检测 PNG 数据的签名是否正确 */
    private static boolean checkSignature(InputStream in)
            throws IOException, NullPointerException {
        byte[] test = new byte[8];
        if (in.read(test) == 8 && Arrays.equals(test, signature)) {
            return true;
        }
        in.reset();
        return false;
    }

    /* 从输入流里读取一个大端整数 */
    private static int readInteger(InputStream in) throws IOException {
        return (int) (in.read() << 24 | in.read() << 16 | in.read() << 8 | (long) in.read());
    }

    /* 从输入流里读取数据块 */
    private static boolean readChunk(InputStream in, byte[] out) throws IOException {
        return in.read(out) != out.length;
    }

    /* 从输入流里读取图片数据并创建实例 */
    public static SimplePNG fromPNG(InputStream in)
            throws IOException, DataFormatException, IndexOutOfBoundsException {
        SimplePNG png = new SimplePNG();

        if (checkSignature(in)) {  /* 先进行签名检测 */
            while (true) {

                /* 读取 chunk 数据 */
                byte[] chunk = new byte[readInteger(in) + 4];
                if (readChunk(in, chunk)) {
                    break;
                }

                /* 校验 chunk 数据 */
                long check = readInteger(in) & 0xFFFFFFFFL;
                CRC32 crc32 = new CRC32();
                crc32.update(chunk, 0, chunk.length);
                long value = crc32.getValue();
                if (value != check) {
                    throw new IOException("CRC32 校验错误 " + check + " != " + value);
                }

                ByteBuffer inner = ByteBuffer.wrap(chunk).order(ByteOrder.BIG_ENDIAN);
                int name = inner.getInt();  /* 识别 chunk 类型 */

                switch (name) {
                    /* 标准 PNG Chunk 全部大写名称，必须实现的 */
                    case 'I' << 24 | 'H' << 16 | 'D' << 8 | 'R':
                        png.readHeader(inner);
                        break;
                    case 'P' << 24 | 'L' << 16 | 'T' << 8 | 'E':
                        png.readPalette(inner);
                        break;
                    case 'I' << 24 | 'D' << 16 | 'A' << 8 | 'T':
                        png.readData(inner.array());
                        break;
                    case 'I' << 24 | 'E' << 16 | 'N' << 8 | 'D':
                        return png.readEnd();

                    /* 透明度和背景，常用的 */
                    case 't' << 24 | 'R' << 16 | 'N' << 8 | 'S':
                        png.readTransparent(inner);
                        break;
                    case 'b' << 24 | 'K' << 16 | 'G' << 8 | 'D':
                        png.readBlackGround(inner);
                        break;

                    // default:
                    //     throw new IOException(getChunkName(name) + " Not Imp Yet.");
                }
            }
        }

        return png.readEnd();
    }

    public static SimplePNG fromPNG(byte[] png)
            throws IOException, DataFormatException, IndexOutOfBoundsException {
        return fromPNG(new ByteArrayInputStream(png));
    }

    private final class Thumb {
        private final int h, w;
        private final byte[][] inner;
        private int y, x;

        private Thumb(int height, int width) {
            this(height, width, 0);
        }

        private Thumb(int height, int width, int start) {
            h = height;
            w = width * pixel_size + 1;
            inner = new byte[h][w];
            y = 0;
            x = start;
        }

        private void unSub(byte[] current) {
            for (int i = 1, j = i + pixel_size; j < w; i++, j++) {
                int a = current[i] & 0xff;
                int x = current[j] & 0xff;
                current[j] = (byte) (x + a);
            }
        }

        private void unUp(byte[] previous, byte[] current) {
            for (int i = 1; i < w; i++) {
                int x = current[i] & 0xff;
                int b = previous[i] & 0xff;
                current[i] = (byte) (x + b);
            }
        }

        private void unAverage(byte[] previous, byte[] current) {
            for (int i = 1 - pixel_size, j = 1; j < w; i++, j++) {
                int x = current[j] & 0xff;
                int a = (i < 1) ? 0 : (current[i] & 0xff);
                int b = previous[j] & 0xff;
                current[j] = (byte) (x + ((a + b) >> 1));
            }
        }

        private void unPredictor(byte[] previous, byte[] current) {
            for (int i = 1 - pixel_size, j = 1; j < w; i++, j++) {
                int x = current[j] & 0xff;
                int pr;
                if (i < 1) {
                    pr = previous[j] & 0xff;
                } else {
                    int a = current[i] & 0xff;
                    int c = previous[i] & 0xff;
                    int b = previous[j] & 0xff;
                    int pa = Math.abs(b - c);
                    int pb = Math.abs(a - c);
                    int pc = Math.abs(a + b - c - c);
                    if (pa <= pb && pa <= pc) {
                        pr = a;
                    } else if (pb <= pc) {
                        pr = b;
                    } else {
                        pr = c;
                    }
                }
                current[j] = (byte) (x + pr);
            }
        }

        private boolean fillFilteredData(byte b) {
            inner[y][x] = b;
            x += 1;
            if (x >= w) {
                x = 0;
                y += 1;
                if (y < h) {
                    return false;
                } else {
                    y = 0;
                    x = step_size;
                    return true;
                }
            }
            return false;
        }

        private void unFilter() {
            byte[] previous_line = null;

            for (byte[] current_line : inner) {
                int filter_mask = current_line[0];
                if (filter_mask == 1) {
                    unSub(current_line);
                } else if (filter_mask == 2) {
                    assert previous_line != null;
                    unUp(previous_line, current_line);
                } else if (filter_mask == 3) {
                    assert previous_line != null;
                    unAverage(previous_line, current_line);
                } else if (filter_mask == 4) {
                    assert previous_line != null;
                    unPredictor(previous_line, current_line);
                }
                previous_line = current_line;
            }
        }

        private int nextImageData() {
            int result = inner[y][x] & 0xff;
            x += step_size;
            if (x >= w) {
                x = step_size;
                y += 1;
                if (y >= h) {
                    y = 0;
                }
            }
            return result;
        }

        private void fillImageData(byte b) {
            inner[y][x] = b;
            x += step_size;
            if (x >= w) {
                x = step_size;
                y += 1;
                if (y >= h) {
                    y = 0;
                }
            }
        }

        public byte[] nextFilterLine() {
            return inner[y++];
        }
    }

    private enum Mode {
        SINGLE, RGB, RGB_ALPHA
    }

    public static Mode SINGLE = Mode.SINGLE;
    public static Mode RGB = Mode.RGB;
    public static Mode RGB_ALPHA = Mode.RGB_ALPHA;

    private static final double POW = 0.75;

    private int width, height, image_size;  /* 图片的宽、高、尺寸 */
    private byte type;
    private boolean interlace;  /* 交错模式 */
    private int pixel_size, cache_size, step_size;  /* 像素字节长度、解压缓存长度、像素筛选 */
    private Inflater decompress;  /* 解压器 */
    private Thumb[] thumbnails;  /* 缩略图表 */
    private Thumb thumb;
    private int index;
    private int[][] palette;
    private int[] transparent;
    private int[] background;
    private byte[] data;
    private Mode mode;

    /* 读取 Header 数据块 */
    private void readHeader(ByteBuffer buffer) {
        width = buffer.getInt(); /* 第一个整数是图片的宽度 */
        height = buffer.getInt(); /* 第二个整数是图片的高度 */
        image_size = width * height;
        byte depth = buffer.get(); /* 接下来是图片深度，深度为 8 位（常用）或者 16 位 */
        type = buffer.get(); /* 接下来是图片类型，也就是上面的 GRAY RGB 之类的常量 */
        buffer.get(); /* 用户自定义的压缩方法，这里没有自定义所以是无用数据 */
        buffer.get(); /* 用户自定义的滤波方法，这里没有自定义所以是无用数据 */
        interlace = buffer.get() != 0; /* 交错模式标记 */
        byte plane = COLOR_PLANES[type]; /* 图片的通道数量 */
        pixel_size = (depth * plane + 7) / 8; /* 图片像素占据的内存长度 */
        cache_size = pixel_size * (int) Math.pow(image_size, POW); /* 解压需要设置缓存大小 */
        step_size = depth / 8; /* 目前的硬件无法读取 16 位深度，图片深度为 16 时，需要跳过一些内容 */
        decompress = new Inflater(); /* 解压器 */

        if (interlace) { /* 交错模式下，需要创建七个预览图，预览图按照 ADAM7 矩阵交错可以还原原始图片 */
            thumbnails = new Thumb[]{
                    new Thumb((height + 7) / 8, (width + 7) / 8),
                    new Thumb((height + 7) / 8, (width + 3) / 8),
                    new Thumb((height + 3) / 8, (width + 3) / 4),
                    new Thumb((height + 3) / 4, (width + 1) / 4),
                    new Thumb((height + 1) / 4, (width + 1) / 2),
                    new Thumb((height + 1) / 2, width / 2),
                    new Thumb(height / 2, width)
            };
        } else { /* 普通模式下只需要一个预览图 */
            thumbnails = new Thumb[]{new Thumb(height, width)};
        }
        thumb = thumbnails[0];
        index = 0;
    }

    /* 读取索引数据块 */
    private void readPalette(ByteBuffer buffer) {
        /* assert type == PALETTE; // 图片需要是索引模式，不然就是有点问题 */
        int remaining = buffer.remaining();
        int limit = remaining / 3;
        palette = new int[limit][3]; /* 索引带有 RGB 三个通道 */

        for (int x = 0; x < limit; x++) {
            for (int y = 0; y < 3; y++) {
                palette[x][y] = buffer.get() & 0xff;
            }
        }
    }

    /* 读取透明数据块 */
    private void readTransparent(ByteBuffer buffer) {
        if (type == TYPE_GRAY) {
            transparent = new int[]{buffer.getShort() & 0xff}; /* 为了兼容 16 位深度，所以是 short */
        } else if (type == TYPE_RGB) {
            transparent = new int[]{ /* 但是硬件无法展示 16 位深度，所以得抛弃一些数据 */
                    buffer.getShort() & 0xff, buffer.getShort() & 0xff, buffer.getShort() & 0xff};
        } else if (type == TYPE_PALETTE) {
            int length = buffer.remaining();
            transparent = new int[length];
            for (int i = 0; i < length; i++) {
                transparent[i] = buffer.get() & 0xff; /* 索引区只有 256 大小 */
            }
            /* for (int i = length; i < 256; i++) transparent[i] = 255; */
        }
    }

    /* 读取背景数据块 */
    private void readBlackGround(ByteBuffer buffer) {
        if (type == TYPE_GRAY || type == TYPE_GRAY_ALPHA) {
            background = new int[]{buffer.getShort() & 0xff};
        } else if (type == TYPE_RGB || type == TYPE_RGB_ALPHA) {
            transparent = new int[]{
                    buffer.getShort() & 0xff, buffer.getShort() & 0xff, buffer.getShort() & 0xff};
        } else if (type == TYPE_PALETTE) {
            background = new int[]{buffer.get() & 0xff};  /* 指向 PALETTE */
        }
    }

    /* 像预览图里填充数据 */
    private void fillFilteredData(byte b) throws IndexOutOfBoundsException {
        if (thumb == null) {
            throw new IndexOutOfBoundsException();
        }

        if (thumb.fillFilteredData(b)) { /* 预览图填满了 */
            thumb.unFilter(); /* 进行反向滤波 */
            index += 1; /* 切换新的预览图 */
            if (index < thumbnails.length) {
                thumb = thumbnails[index];
            } else {
                thumb = null;
            }
        }
    }

    /* 读取 Data 数据块，读取、解压、填入预览图 */
    private void readData(byte[] buffer) throws DataFormatException, IndexOutOfBoundsException {
        decompress.setInput(buffer, 4, buffer.length - 4);

        byte[] cache = new byte[cache_size];

        while (true) {
            int size = decompress.inflate(cache);
            /* System.out.println(size); */

            for (int i = 0; i < size; i++) {
                fillFilteredData(cache[i]);
            }
            if (size != cache_size) {
                break;
            }
        }
    }

    /* 将图片转换为缺省图片 */
    private static byte[] defaultImage() {
        byte[] result = new byte[9216];
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(DEFAULT_IMAGE);
            inflater.inflate(result);
            inflater.end();
        } catch (DataFormatException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    /* 将预览图的数据合并到原始图上，由于每种图片类型的像素数据量是不同的，所以这里参数是一个接收器 */
    private void fillImageData(Consumer<Thumb> action) { /* 每个接收器处理一个像素的数据 */
        if (interlace) {
            for (int h = 0; h < height; h++) {
                byte[] adam7 = ADAM7[h % 8]; /* 交错模式下，像素的位置会切换 */
                for (int w = 0; w < width; w++) {
                    action.accept(thumbnails[adam7[w % 8]]);
                }
            }
        } else {
            for (int i = 0; i < image_size; i++) {
                action.accept(thumbnails[0]);
            }
        }
    }

    /* 颜色通道的插值 */
    private byte erp(int background, int pixel, int alpha) {
        return (byte) Math.round(background + (pixel - background) * alpha / 255f);
    }

    /* 读取结束，将预览图合并为原始图 */
    private SimplePNG readEnd() {
        if (decompress != null) { /* 先把不需要的解压器关闭了 */
            decompress.end();
            decompress = null;
        }

        if (thumbnails == null) { /* 如果没有读取到预览图数据，返回缺省图片 */
            // Log.warn(new Exception("没有读取到预览图数据"));
            data = defaultImage();
            width = 48;
            height = 48;
            image_size = 48 * 48;
            type = TYPE_RGB_ALPHA;
            pixel_size = 4;
            cache_size = 4 * (int) Math.pow(image_size, POW);
            mode = Mode.RGB_ALPHA;

        } else {
            index = 0;

            switch (type) {
                case TYPE_GRAY:
                    if (transparent == null) { /* 无额外透明标记，不会进行改变 */
                        mode = Mode.SINGLE;
                        /* type = GRAY; */
                        pixel_size = 1;
                        cache_size = (int) Math.pow(image_size, POW);
                        data = new byte[image_size];

                        fillImageData(t -> data[index++] = (byte) t.nextImageData());
                    } else {
                        int alpha = transparent[0];

                        if (background == null) { /* 有透明无背景，转换为 RGBA 模式 */
                            mode = Mode.RGB_ALPHA;
                            type = TYPE_RGB_ALPHA;
                            pixel_size = 4;
                            cache_size = 4 * (int) Math.pow(image_size, POW);
                            data = new byte[image_size * 4];

                            fillImageData(t -> {
                                byte gray = (byte) t.nextImageData();
                                data[index++] = gray;
                                data[index++] = gray;
                                data[index++] = gray;
                                data[index++] = (byte) alpha;
                            });
                        } else { /* 有透明有背景，融合成单通道 */
                            mode = Mode.SINGLE;
                            pixel_size = 1;
                            cache_size = (int) Math.pow(image_size, POW);
                            data = new byte[image_size];

                            int b = background[0];
                            fillImageData(t -> data[index++] = erp(b, t.nextImageData(), alpha));
                        }
                    }
                    break;
                /* 这是原生双通道的图片，需要进行融合 */
                case TYPE_GRAY_ALPHA:
                    if (background == null) { /* 本身具有透明通道，不在乎额外透明的标记 */
                        mode = Mode.RGB_ALPHA;
                        type = TYPE_RGB_ALPHA;
                        pixel_size = 4;
                        cache_size = 4 * (int) Math.pow(image_size, POW);
                        data = new byte[image_size * 4];

                        fillImageData(t -> {
                            byte gray = (byte) t.nextImageData();
                            data[index++] = gray;
                            data[index++] = gray;
                            data[index++] = gray;
                            data[index++] = (byte) t.nextImageData();
                        });
                    } else {
                        mode = Mode.RGB;
                        type = TYPE_RGB;
                        pixel_size = 3;
                        cache_size = 3 * (int) Math.pow(image_size, POW);
                        data = new byte[image_size * 3];

                        int back = background[0];
                        fillImageData(t -> {
                            int gray = t.nextImageData();
                            int alpha = t.nextImageData();
                            data[index++] = erp(back, gray, alpha);
                        });
                    }
                    break;
                /* 索引模式需要对照索引还原 */
                case TYPE_PALETTE:
                    if (transparent == null) {
                        mode = Mode.RGB;
                        type = TYPE_RGB;
                        pixel_size = 3;
                        cache_size = 3 * (int) Math.pow(image_size, POW);
                        data = new byte[image_size * 3];

                        fillImageData(t -> {
                            int[] rgb = palette[t.nextImageData()];
                            data[index++] = (byte) rgb[0];
                            data[index++] = (byte) rgb[1];
                            data[index++] = (byte) rgb[2];
                        });
                    } else {
                        if (background == null) {
                            mode = Mode.RGB_ALPHA;
                            type = TYPE_RGB_ALPHA;
                            pixel_size = 4;
                            cache_size = 4 * (int) Math.pow(image_size, POW);
                            data = new byte[image_size * 4];

                            fillImageData(t -> {
                                int m = t.nextImageData();
                                int[] rgb = palette[m];
                                data[index++] = (byte) rgb[0];
                                data[index++] = (byte) rgb[1];
                                data[index++] = (byte) rgb[2];
                                data[index++] = (byte) transparent[m];
                            });
                        } else {
                            mode = Mode.RGB;
                            type = TYPE_RGB;
                            pixel_size = 3;
                            cache_size = 3 * (int) Math.pow(image_size, POW);
                            data = new byte[image_size * 3];

                            int[] back = palette[background[0]];
                            fillImageData(t -> {
                                int m = t.nextImageData();
                                int[] rgb = palette[m];
                                int a = transparent[m];
                                data[index++] = erp(back[0], rgb[0], a);
                                data[index++] = erp(back[1], rgb[1], a);
                                data[index++] = erp(back[2], rgb[2], a);
                            });
                        }
                    }
                    break;
                case TYPE_RGB:
                    if (transparent == null) {
                        mode = Mode.RGB;
                        pixel_size = 3;
                        cache_size = 3 * (int) Math.pow(image_size, POW);
                        data = new byte[image_size * 3];

                        fillImageData(t -> {
                            data[index++] = (byte) t.nextImageData();
                            data[index++] = (byte) t.nextImageData();
                            data[index++] = (byte) t.nextImageData();
                        });
                    } else {
                        int ra = transparent[0];
                        int ga = transparent[1];
                        int ba = transparent[2];

                        if (background == null) {
                            mode = Mode.RGB_ALPHA;
                            type = TYPE_RGB_ALPHA;
                            pixel_size = 4;
                            cache_size = 4 * (int) Math.pow(image_size, POW);
                            data = new byte[image_size * 4];

                            float alpha = ra > ga ? (Math.max(ra, ba)) : (Math.max(ga, ba));
                            fillImageData(t -> {
                                data[index++] = (byte) Math.round(t.nextImageData() * ra / alpha);
                                data[index++] = (byte) Math.round(t.nextImageData() * ga / alpha);
                                data[index++] = (byte) Math.round(t.nextImageData() * ba / alpha);
                                data[index++] = (byte) alpha;
                            });
                        } else {
                            mode = Mode.RGB;
                            pixel_size = 3;
                            cache_size = 3 * (int) Math.pow(image_size, POW);
                            data = new byte[image_size * 3];

                            fillImageData(t -> {
                                data[index++] = erp(background[0], t.nextImageData(), ra);
                                data[index++] = erp(background[1], t.nextImageData(), ga);
                                data[index++] = erp(background[2], t.nextImageData(), ba);
                            });
                        }
                    }
                    break;
                case TYPE_RGB_ALPHA:
                    if (background == null) {
                        mode = Mode.RGB_ALPHA;
                        pixel_size = 4;
                        cache_size = 4 * (int) Math.pow(image_size, POW);
                        data = new byte[image_size * 4];

                        fillImageData(t -> {
                            data[index++] = (byte) t.nextImageData();
                            data[index++] = (byte) t.nextImageData();
                            data[index++] = (byte) t.nextImageData();
                            data[index++] = (byte) t.nextImageData();
                        });
                    } else {
                        mode = Mode.RGB;
                        type = TYPE_RGB;
                        pixel_size = 3;
                        cache_size = 3 * (int) Math.pow(image_size, POW);
                        data = new byte[image_size * 3];

                        int rb = background[0];
                        int gb = background[1];
                        int bb = background[2];
                        fillImageData(t -> {
                            int rc = t.nextImageData();
                            int gc = t.nextImageData();
                            int bc = t.nextImageData();
                            int ac = t.nextImageData();
                            data[index++] = erp(rb, rc, ac);
                            data[index++] = erp(gb, gc, ac);
                            data[index++] = erp(bb, bc, ac);
                        });
                    }
                    break;
                /* default -> {} // 正常情况下不会遇到特殊类型的 PNG 图片 */
            }

            thumbnails = null;
        }

        interlace = false; /* 清除数据 */
        step_size = 1;
        /* index = 0; */
        palette = null;
        transparent = null;
        background = null;

        return this;
    }

    public void toPNG(OutputStream out) throws IOException {
        DataOutputStream stream = new DataOutputStream(out);
        Deflater compress = new Deflater();
        CRC32 crc32 = new CRC32();

        byte[] head_chunk = {'I', 'H', 'D', 'R',
                (byte) (width >> 24), (byte) (width >> 16), (byte) (width >> 8), (byte) width,
                (byte) (height >> 24), (byte) (height >> 16), (byte) (height >> 8), (byte) height,
                8, type, 0, 0, /* interlace ? (byte) 1 : */ 0};

        crc32.update(head_chunk, 0, head_chunk.length);

        Thumb t = new Thumb(height, width, step_size);
        for (byte b : data) {
            t.fillImageData(b);
        }

        byte[] data_mask = {'I', 'D', 'A', 'T'};
        byte[] cache = new byte[cache_size];

        byte[] end_chunk = {0, 0, 0, 0, 'I', 'E', 'N', 'D', -82, 66, 96, -126};

        boolean loop = true;
        index = 0;

        stream.write(signature);
        stream.writeInt(head_chunk.length - 4);
        stream.write(head_chunk);
        stream.writeInt((int) crc32.getValue());

        while (loop) {
            if (index < height) {
                compress.setInput(t.nextFilterLine());
                index += 1;
            } else {
                compress.finish();
                loop = false;
            }

            while (true) {
                int size = compress.deflate(cache);
                /* System.out.println(size); */

                if (size != 0) {
                    crc32.reset();
                    crc32.update(data_mask, 0, data_mask.length);
                    crc32.update(cache, 0, size);

                    stream.writeInt(size);
                    stream.write(data_mask);
                    stream.write(cache, 0, size);
                    stream.writeInt((int) crc32.getValue());
                }

                if (size != cache_size) {
                    break;
                }
            }
        }

        stream.write(end_chunk);

    }

    public byte[] toPNG() throws IOException {
        ByteArrayOutputStream cache = new ByteArrayOutputStream();
        toPNG(cache);
        return cache.toByteArray();
    }

    /* 直接把数组作为图片数据，注意暴露风险 */
    public static SimplePNG fromDirectIMAGE(byte[] data, int width, int height) {
        SimplePNG png = new SimplePNG();

        float k = (float) data.length / width / height;

        if (k == 1f) {
            png.type = TYPE_GRAY;
            png.data = data;
            png.width = width;
            png.height = height;
            png.image_size = width * height;
            png.step_size = 1;
            png.pixel_size = 1;
            png.cache_size = (int) (Math.pow(png.image_size, POW));
            png.mode = Mode.SINGLE;
        } else if (k == 3f) {
            png.type = TYPE_RGB;
            png.data = data;
            png.width = width;
            png.height = height;
            png.image_size = width * height;
            png.step_size = 1;
            png.pixel_size = 3;
            png.cache_size = 3 * (int) (Math.pow(png.image_size, POW));
            png.mode = Mode.RGB;
        } else if (k == 4f) {
            png.type = TYPE_RGB_ALPHA;
            png.data = data;
            png.width = width;
            png.height = height;
            png.image_size = width * height;
            png.step_size = 1;
            png.pixel_size = 4;
            png.cache_size = 4 * (int) (Math.pow(png.image_size, POW));
            png.mode = Mode.RGB_ALPHA;
        } else {
            // Log.warn(new Exception("byte[].length (" + data.length + ") is nonsupport."));
            png.type = TYPE_RGB_ALPHA;
            png.data = defaultImage();
            png.width = 48;
            png.height = 48;
            png.image_size = 48 * 48;
            png.step_size = 1;
            png.pixel_size = 4;
            png.cache_size = 4 * (int) (Math.pow(png.image_size, POW));
            png.mode = Mode.RGB_ALPHA;
        }

        return png;
    }

    /* 把数组复制后作为图片数据，安全但是浪费 */
    public static SimplePNG fromIMAGE(byte[] data, int width, int height) {
        return fromDirectIMAGE(Arrays.copyOf(data, data.length), width, height);
    }

    public static SimplePNG fromIMAGE() {
        SimplePNG png = new SimplePNG();
        png.type = TYPE_RGB_ALPHA;
        png.data = defaultImage();
        png.width = 48;
        png.height = 48;
        png.image_size = 48 * 48;
        png.step_size = 1;
        png.pixel_size = 4;
        png.cache_size = 4 * (int) (Math.pow(png.image_size, POW));
        png.mode = Mode.RGB_ALPHA;
        return png;
    }

    public byte[] toIMAGE() {
        return Arrays.copyOf(data, data.length);
    }

    /* 注意，修改 data 会改动原图 */
    public byte[] getInnerData() {
        return data;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Mode getMode() {
        return mode;
    }

    @Override
    public SimplePNG clone() {
        try {
            SimplePNG png = (SimplePNG) super.clone();
            png.data = Arrays.copyOf(data, data.length);
            return png;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    private void scaleWidth2First(
            int data_offset, float data_size_dec, float[] first, int first_size_dec) {

        for (int first_pos = 0; first_pos <= first_size_dec; first_pos++) {
            int first_offset = first_pos * pixel_size;

            float data_pos = first_pos * data_size_dec / first_size_dec;
            int data_floor = (int) Math.floor(data_pos);  /* 新像素对应原始图片位置的 floor */
            int data_floor_offset = data_offset + data_floor * pixel_size;

            if (data_pos == data_floor) {
                for (int i = 0; i < pixel_size; i++) {
                    first[first_offset + i] = data[data_floor_offset + i] & 0xff;
                }

            } else {
                int data_ceil = (int) Math.ceil(data_pos);  /* 新像素对应原始图片位置的 ceil */
                int data_ceil_offset = data_offset + data_ceil * pixel_size;

                float floor2pos = data_pos - data_floor;
                float pos2ceil = data_ceil - data_pos;

                for (int i = 0; i < pixel_size; i++) {
                    int a = data[data_floor_offset + i] & 0xff;
                    int b = data[data_ceil_offset + i] & 0xff;
                    first[first_offset + i] = a * pos2ceil + b * floor2pos;
                }
            }
        }
    }

    /* 将原图缓存转换为放缩后缓存 */
    private void scaleFirst2Second(
            float[] first, int first_size_dec, float[] second, int second_size_dec) {

        if (first_size_dec == second_size_dec) {  /* 两个缓存相同，只需要进行复制就可以 */
            System.arraycopy(first, 0, second, 0, first.length);
            return;
        }

        if (first_size_dec < second_size_dec) {  /* 原图缓存小，进行扩张 */
            int enlarge = second_size_dec / first_size_dec;  /* 必然是整数倍 */

            double pi_2 = Math.PI / 2;
            double pi_4 = pi_2 / 2;

            /* 扫描原图缓存像素（除了最后一个像素） */
            for (int first_pos = 0; first_pos < first_size_dec; first_pos++) {
                int a_offset = first_pos * pixel_size;  /* 当前像素 */
                int b_offset = a_offset + pixel_size;  /* 下一个像素 */
                int second_offset = a_offset * enlarge;  /* 当前像素对应放缩后缓存的偏移 */

                /* 像素的每一个通道 */
                for (int i = 0; i < pixel_size; i++) {
                    float a = first[a_offset + i];  /* 当前像素当前通道 */
                    float b = first[b_offset + i];  /* 下一个像素当前通道 */

                    /* 进行差值扩张 */
                    for (int e = 0; e < enlarge; e++) {
                        float k = ((float) Math.sin(e * pi_2 / enlarge - pi_4) + 1) / 2;
                        second[second_offset + i + e * pixel_size] = a * (1 - k) + b * k;
                    }
                }
            }

            /* 复制最后一个像素 */
            System.arraycopy(first, first_size_dec * pixel_size,
                    second, second_size_dec * pixel_size, pixel_size);

        } else {
            int shrink = first_size_dec / second_size_dec;  /* 原图缓存大，进行缩小 */
            int shrink_inc = shrink + 1;

            /* 以缩小图缓存的像素为基点，第一次扫描，只提取关键点 */
            for (int second_pos = 0; second_pos <= second_size_dec; second_pos++) {
                int second_offset = second_pos * pixel_size;  /* 当前像素 */
                int first_offset = second_offset * shrink;  /* 当前像素对应原图缓存的位置 */

                System.arraycopy(first, first_offset, second, second_offset, pixel_size);
            }

            /* 第二次扫描，获取关键点之间的像素信息，用于微调 */
            for (int second_pos = 0; second_pos < second_size_dec; second_pos++) {
                int second_a = second_pos * pixel_size;  /* 当前像素 */
                int second_b = second_a + pixel_size;  /* 下一个像素 */
                int first_a = second_a * shrink;  /* 当前像素对应原图缓存的位置 */
                int first_b = second_b * shrink;  /* 下一个像素像素对应原图缓存的位置 */

                for (int i = 0; i < pixel_size; i++) {
                    float summary = first[first_a + i] + first[first_b + i];
                    float average = summary / 2;
                    for (int s = 1; s < shrink; s++) {
                        summary += first[first_a + i + s * pixel_size];
                    }
                    float adjust = summary / shrink_inc - average;

                    float a = second[second_a + i];
                    float b = second[second_b + i];

                    a += adjust;
                    if (a > 255) {
                        b += a - 255;
                        a = 255;
                    } else if (a < 0) {
                        b += a;
                        a = 0;
                    }

                    b += adjust;
                    if (b > 255) {
                        a += b - 255;
                        b = 255;
                    } else if (b < 0) {
                        a += b;
                        b = 0;
                    }

                    if (a > 255) {  /* 由于浮点数有一定的误差，这里需要进行修正 */
                        a = 255;
                    } else if (a < 0) {
                        a = 0;
                    }

                    second[second_a + i] = a;
                    second[second_b + i] = b;
                }
            }
        }
    }

    private void scaleSecond2Width(
            float[] second, float second_size_dec,
            byte[] last, int last_start, float last_size_dec) {

        for (int last_pos = 0; last_pos <= last_size_dec; last_pos++) {
            int last_offset = last_start + last_pos * pixel_size;

            float second_pos = last_pos / last_size_dec * second_size_dec;
            int second_floor = (int) Math.floor(second_pos);  /* 新像素对应原始图片位置的 floor */
            int second_floor_offset = second_floor * pixel_size;

            if (second_pos == second_floor) {
                for (int i = 0; i < pixel_size; i++) {
                    last[last_offset + i] = (byte) Math.round(second[second_floor_offset + i]);
                }

            } else {
                int second_ceil = (int) Math.ceil(second_pos);  /* 新像素对应原始图片位置的 ceil */
                int second_ceil_offset = second_ceil * pixel_size;

                float floor2pos = second_pos - second_floor;
                float pos2ceil = second_ceil - second_pos;

                for (int i = 0; i < pixel_size; i++) {
                    float a = second[second_floor_offset + i];
                    float b = second[second_ceil_offset + i];
                    last[last_offset + i] = (byte) Math.round(a * pos2ceil + b * floor2pos);
                }
            }
        }
    }

    private void scaleHeight2First(
            int column, int stride, float data_size_dec, float[] first, int first_size_dec) {

        for (int first_pos = 0; first_pos <= first_size_dec; first_pos++) {
            int first_offset = first_pos * pixel_size;

            float data_pos = first_pos * data_size_dec / first_size_dec;
            int data_floor = (int) Math.floor(data_pos);  /* 新像素对应原始图片位置的 floor */
            int data_floor_offset = column + data_floor * stride;

            if (data_pos == data_floor) {
                for (int i = 0; i < pixel_size; i++) {
                    first[first_offset + i] = data[data_floor_offset + i] & 0xff;
                }

            } else {
                int data_ceil = (int) Math.ceil(data_pos);  /* 新像素对应原始图片位置的 ceil */
                int data_ceil_offset = column + data_ceil * stride;

                float floor2pos = data_pos - data_floor;
                float pos2ceil = data_ceil - data_pos;

                for (int i = 0; i < pixel_size; i++) {
                    int a = data[data_floor_offset + i] & 0xff;
                    int b = data[data_ceil_offset + i] & 0xff;
                    first[first_offset + i] = a * pos2ceil + b * floor2pos;
                }
            }
        }
    }

    private void scaleSecond2Height(
            int column, int stride, float[] second, float second_size_dec,
            byte[] last, float last_size_dec) {

        for (int last_pos = 0; last_pos <= last_size_dec; last_pos++) {
            int last_offset = column + last_pos * stride;

            float second_pos = last_pos / last_size_dec * second_size_dec;
            int second_floor = (int) Math.floor(second_pos);  /* 新像素对应原始图片位置的 floor */
            int second_floor_offset = second_floor * pixel_size;

            if (second_pos == second_floor) {
                for (int i = 0; i < pixel_size; i++) {
                    last[last_offset + i] = (byte) Math.round(second[second_floor_offset + i]);
                }

            } else {
                int second_ceil = (int) Math.ceil(second_pos);  /* 新像素对应原始图片位置的 ceil */
                int second_ceil_offset = second_ceil * pixel_size;

                float floor2pos = second_pos - second_floor;
                float pos2ceil = second_ceil - second_pos;

                for (int i = 0; i < pixel_size; i++) {
                    float a = second[second_floor_offset + i];
                    float b = second[second_ceil_offset + i];
                    last[last_offset + i] = (byte) Math.round(a * pos2ceil + b * floor2pos);
                }
            }
        }
    }

    /**
     * 放缩采用三步方式
     * <p>
     * <p> 第一步将每行数据扩大到 1～2 倍大小，这个大小设为 X
     * <p> 第二步将 X 扩大或者缩小到 Y ，X-1 和 Y-1 是整数倍关系， Y 是目标宽度的 1～2 倍
     * <p> 第三步将 Y 缩小到目标宽度
     * <p>
     * <p> 宽度的 1～2 倍，意味着每个目标像素都能在原始像素中进行差值
     */
    private byte[] scaleWidth0(int w) {
        byte[] last = new byte[height * w * pixel_size];

        if (w == 1) { /* 放缩需要两端数据进行差值，当宽度为一时无法差值，需要特殊对待 */
            for (int row = 0; row < height; row++) {
                int rp = row * pixel_size;
                int rpw = rp * width;

                for (int i = 0; i < pixel_size; i++) {
                    float summary = 0;
                    for (int pixel = 0; pixel < width; pixel++) {
                        summary += data[rpw + pixel * pixel_size + i] & 0xff;
                    }
                    last[rp + i] = (byte) Math.round(summary / width); /* 将一行的像素进行平均 */
                }
            }
            return last;
        }

        float data_size_dec = width - 1;
        float last_size_dec = w - 1;

        int first_size_dec = 1 << (int) Math.ceil(Math.log(data_size_dec) / Math.log(2));
        int second_size_dec = 1 << (int) Math.ceil(Math.log(last_size_dec) / Math.log(2));

        float[] first = new float[(first_size_dec + 1) * pixel_size];

        if (first_size_dec == second_size_dec) {
            for (int row = 0; row < height; row++) {
                int rp = row * pixel_size;
                scaleWidth2First(rp * width, data_size_dec, first, first_size_dec);
                scaleSecond2Width(first, second_size_dec, last, rp * w, last_size_dec);
            }
            return last;
        }

        float[] second = new float[(second_size_dec + 1) * pixel_size];

        for (int row = 0; row < height; row++) {
            int rp = row * pixel_size;

            scaleWidth2First(rp * width, data_size_dec, first, first_size_dec);
            scaleFirst2Second(first, first_size_dec, second, second_size_dec);
            scaleSecond2Width(second, second_size_dec, last, rp * w, last_size_dec);
        }

        return last;
    }

    private byte[] scaleHeight0(int h) {
        int stride = width * pixel_size;
        byte[] last = new byte[h * stride];

        if (h == 1) {
            for (int column = 0; column < width; column++) {
                int cp = column * pixel_size;

                for (int i = 0; i < pixel_size; i++) {
                    float summary = 0;
                    for (int pixel = 0; pixel < height; pixel++) {
                        summary += data[cp + pixel * stride + i] & 0xff;
                    }
                    last[cp + i] = (byte) Math.round(summary / height);
                }
            }
            return last;
        }
        float data_size_dec = height - 1;
        float last_size_dec = h - 1;

        int first_size_dec = 1 << (int) Math.ceil(Math.log(data_size_dec) / Math.log(2));
        int second_size_dec = 1 << (int) Math.ceil(Math.log(last_size_dec) / Math.log(2));

        float[] first = new float[(first_size_dec + 1) * pixel_size];

        if (first_size_dec == second_size_dec) {
            for (int column = 0; column < width; column++) {
                int cp = column * pixel_size;
                scaleHeight2First(cp, stride, data_size_dec, first, first_size_dec);
                scaleSecond2Height(cp, stride, first, second_size_dec, last, last_size_dec);
            }
            return last;
        }

        float[] second = new float[(second_size_dec + 1) * pixel_size];

        for (int column = 0; column < width; column++) {
            int cp = column * pixel_size;

            scaleHeight2First(cp, stride, data_size_dec, first, first_size_dec);
            scaleFirst2Second(first, first_size_dec, second, second_size_dec);
            scaleSecond2Height(cp, stride, second, second_size_dec, last, last_size_dec);
        }
        return last;
    }

    public SimplePNG scaleWidth(int w) {
        if (w > 0) {
            data = scaleWidth0(w);
            width = w;
            image_size = width * height;
            cache_size = pixel_size * (int) Math.pow(image_size, POW);
        }
        return this;
    }

    public SimplePNG scaleHeight(int h) {
        if (h > 0) {
            data = scaleHeight0(h);
            height = h;
            image_size = width * height;
            cache_size = pixel_size * (int) Math.pow(image_size, POW);
        }
        return this;
    }

    public SimplePNG cloneAndScaleWidth(int w) {
        if (w <= 0) {
            return clone();
        }
        return fromDirectIMAGE(scaleWidth0(w), w, height);
    }

    public SimplePNG cloneAndScaleHeight(int h) {
        if (h <= 0) {
            return clone();
        }
        return fromDirectIMAGE(scaleHeight0(h), width, h);
    }

    private byte[] convertToSINGLE(int bkg) {
        byte[] last = new byte[image_size];

        if (mode == Mode.SINGLE) {
            System.arraycopy(data, 0, last, 0, image_size);

        } else if (mode == Mode.RGB) {
            for (int i = 0; i < image_size; i++) {
                int offset = i * 3;
                float summary = 0;
                for (int p = 0; p < 3; p++) {
                    summary += Math.pow(data[offset + p] & 0xff, 2);
                }
                last[i] = (byte) Math.round(Math.pow(summary / 3, 0.5));
            }

        } else if (mode == Mode.RGB_ALPHA) {
            for (int i = 0; i < image_size; i++) {
                int offset = i * 4;
                float summary = 0;
                for (int p = 0; p < 3; p++) {
                    summary += Math.pow(data[offset + p] & 0xff, 2);
                }
                float a = (data[offset + 3] & 0xff) / 255f;
                last[i] = (byte) Math.round(bkg * (1 - a) + Math.pow(summary / 3, 0.5) * a);
            }
        }

        return last;
    }

    private byte[] convertToRGB(int bkg_red, int bkg_green, int bkg_blue) {
        int size = image_size * 3;
        byte[] last = new byte[size];
        int cache = 0;

        if (mode == Mode.SINGLE) {
            for (int i = 0; i < image_size; i++) {
                byte gray = data[i];
                last[cache++] = gray;
                last[cache++] = gray;
                last[cache++] = gray;
            }

        } else if (mode == Mode.RGB) {
            System.arraycopy(data, 0, last, 0, size);

        } else if (mode == Mode.RGB_ALPHA) {
            for (int i = 0; i < image_size; i++) {
                int offset = i * 4;
                int r = data[offset] & 0xff;
                int g = data[offset + 1] & 0xff;
                int b = data[offset + 2] & 0xff;
                float a = (data[offset + 3] & 0xff) / 255f;

                last[cache++] = (byte) Math.round(bkg_red * (1 - a) + r * a);
                last[cache++] = (byte) Math.round(bkg_green * (1 - a) + g * a);
                last[cache++] = (byte) Math.round(bkg_blue * (1 - a) + b * a);
            }
        }

        return last;
    }

    private byte[] convertToRGB_ALPHA() {
        int size = image_size * 4;
        byte[] last = new byte[size];
        int cache = 0;

        if (mode == Mode.SINGLE) {
            for (int i = 0; i < image_size; i++) {
                byte gray = data[i];
                last[cache++] = gray;
                last[cache++] = gray;
                last[cache++] = gray;
                last[cache++] = -1;
            }
        } else if (mode == Mode.RGB) {
            for (int i = 0; i < image_size; i++) {
                int offset = i * 3;
                last[cache++] = data[offset];
                last[cache++] = data[offset + 1];
                last[cache++] = data[offset + 2];
                last[cache++] = -1;
            }
        } else if (mode == Mode.RGB_ALPHA) {
            System.arraycopy(data, 0, last, 0, size);
        }
        return last;
    }

    public SimplePNG convert(Mode mode) {
        return convert(mode, 255, 255, 255);
    }

    public SimplePNG convert(Mode meow, int r, int b, int g) {
        if (meow == Mode.SINGLE) {
            data = convertToSINGLE(r);
            type = TYPE_GRAY;
            mode = Mode.SINGLE;
            pixel_size = 1;
            cache_size = (int) Math.pow(image_size, POW);

        } else if (meow == Mode.RGB) {
            data = convertToRGB(r, b, g);
            type = TYPE_RGB;
            mode = Mode.RGB;
            pixel_size = 3;
            cache_size = 3 * (int) Math.pow(image_size, POW);

        } else if (meow == Mode.RGB_ALPHA) {
            data = convertToRGB_ALPHA();
            type = TYPE_RGB_ALPHA;
            mode = Mode.RGB_ALPHA;
            pixel_size = 4;
            cache_size = 4 * (int) Math.pow(image_size, POW);
        }

        return this;
    }

    public SimplePNG cloneAndConvert(Mode mode) {
        return cloneAndConvert(mode, 255, 255, 255);
    }

    public SimplePNG cloneAndConvert(Mode mode, int r, int b, int g) {
        if (mode == Mode.SINGLE) {
            return fromDirectIMAGE(convertToSINGLE(r), width, height);
        } else if (mode == Mode.RGB) {
            return fromDirectIMAGE(convertToRGB(r, b, g), width, height);
        } else if (mode == Mode.RGB_ALPHA) {
            return fromDirectIMAGE(convertToRGB_ALPHA(), width, height);
        }
        return clone();
    }

    private void getRGBA(int offset, int[] rgba) {
        if (mode == Mode.SINGLE) {
            rgba[0] = rgba[1] = rgba[2] = data[offset] & 0xff;
            rgba[3] = 255;
        } else if (mode == Mode.RGB) {
            offset *= pixel_size;
            rgba[0] = data[offset] & 0xff;
            rgba[1] = data[offset + 1] & 0xff;
            rgba[2] = data[offset + 2] & 0xff;
            rgba[3] = 255;
        } else if (mode == Mode.RGB_ALPHA) {
            offset *= pixel_size;
            rgba[0] = data[offset] & 0xff;
            rgba[1] = data[offset + 1] & 0xff;
            rgba[2] = data[offset + 2] & 0xff;
            rgba[3] = data[offset + 3] & 0xff;
        }
    }

    private static SimplePNG paste0(SimplePNG base, int x, int y, SimplePNG cover) {
        int base_left = Math.max(0, x);
        int base_top = Math.max(0, y);

        int paste_width = Math.min(base.width, x + cover.width) - base_left;
        int paste_height = Math.min(base.height, y + cover.height) - base_top;

        int cover_left = Math.max(0, -x);
        int cover_top = Math.max(0, -y);

        int[] p = new int[4];

        if (base.mode == Mode.RGB_ALPHA) {
            for (int h = 0; h < paste_height; h++) {
                for (int w = 0; w < paste_width; w++) {
                    cover.getRGBA((h + cover_top) * cover.width + w + cover_left, p);
                    float ac = p[3] / 255f;
                    float ca = 1 - ac;

                    int base_offset = ((h + base_top) * base.width + w + base_left) * 4;
                    int rb = base.data[base_offset] & 0xff;
                    int gb = base.data[base_offset + 1] & 0xff;
                    int bb = base.data[base_offset + 2] & 0xff;
                    float ab = (base.data[base_offset + 3] & 0xff) / 255f;

                    base.data[base_offset] = (byte) Math.round((rb * ab) * ca + p[0] * ac);
                    base.data[base_offset + 1] = (byte) Math.round((gb * ab) * ca + p[1] * ac);
                    base.data[base_offset + 2] = (byte) Math.round((bb * ab) * ca + p[2] * ac);
                    base.data[base_offset + 3] = (byte) Math.round((ab * ca + ac) * 255);
                }
            }
        } else if (base.mode == Mode.RGB) {
            for (int h = 0; h < paste_height; h++) {
                for (int w = 0; w < paste_width; w++) {
                    cover.getRGBA((h + cover_top) * cover.width + w + cover_left, p);
                    float ac = p[3] / 255f;
                    float ca = 1 - ac;

                    int base_offset = ((h + base_top) * base.width + w + base_left) * 3;
                    int rb = base.data[base_offset] & 0xff;
                    int gb = base.data[base_offset + 1] & 0xff;
                    int bb = base.data[base_offset + 2] & 0xff;

                    base.data[base_offset] = (byte) Math.round(rb * ca + p[0] * ac);
                    base.data[base_offset + 1] = (byte) Math.round(gb * ca + p[1] * ac);
                    base.data[base_offset + 2] = (byte) Math.round(bb * ca + p[2] * ac);
                }
            }
        } else if (base.mode == Mode.SINGLE) {
            for (int h = 0; h < paste_height; h++) {
                for (int w = 0; w < paste_width; w++) {
                    cover.getRGBA((h + cover_top) * cover.width + w + cover_left, p);
                    float ac = p[3] / 255f;
                    float ca = 1f - ac;

                    int base_offset = (h + base_top) * base.width + w + base_left;
                    int gb = base.data[base_offset] & 0xff;

                    base.data[base_offset] = (byte) Math.round(gb * ca + ac * Math.pow(
                            (Math.pow(p[0], 2) + Math.pow(p[1], 2) + Math.pow(p[2], 2)) / 3, 0.5));
                }
            }
        }
        return base;
    }

    public SimplePNG paste(SimplePNG other, int x, int y) {
        return paste0(this, x, y, other);
    }

    /* 使用 clone().paste(...) 是一样的效果 */
    public SimplePNG cloneAndPaste(SimplePNG other, int x, int y) {
        return paste0(clone(), x, y, other);
    }

    private SimplePNG() {
    }

    public SimplePNG(int w, int h, int gray) {
        this(w, h, gray, -1, -1, -1);
    }

    public SimplePNG(int w, int h, int r, int g, int b) {
        this(w, h, r, g, b, -1);
    }

    public SimplePNG(int w, int h, int r, int g, int b, int a) {
        width = w;
        height = h;
        image_size = w * h;
        cache_size = 4 * (int) (Math.pow(image_size, POW));
        step_size = 1;

        if (g == -1) {
            type = TYPE_GRAY;
            mode = Mode.SINGLE;
            pixel_size = 1;
            data = new byte[image_size];
            Arrays.fill(data, (byte) r);

        } else if (a == -1) {
            type = TYPE_RGB;
            mode = Mode.RGB;
            pixel_size = 3;
            int size = image_size * 3;
            data = new byte[size];
            for (int i = 0; i < size;) {
                data[i++] = (byte) r;
                data[i++] = (byte) g;
                data[i++] = (byte) b;
            }

        } else {
            type = TYPE_RGB_ALPHA;
            mode = Mode.RGB_ALPHA;
            pixel_size = 4;
            int size = image_size * 4;
            data = new byte[size];
            for (int i = 0; i < size;) {
                data[i++] = (byte) r;
                data[i++] = (byte) g;
                data[i++] = (byte) b;
                data[i++] = (byte) a;
            }

        }

    }

    @Override
    public String toString() {
        return "PNG{" + width + 'x' + height + ", " + mode + '}';
    }

    public static SimplePNG random(Mode m, int w, int h) {
        if (m == Mode.SINGLE) {
            byte[] random = new byte[w * h];
            new Random().nextBytes(random);
            return fromDirectIMAGE(random, w, h);
        } else if (m == Mode.RGB) {
            byte[] random = new byte[w * h * 3];
            new Random().nextBytes(random);
            return fromDirectIMAGE(random, w, h);
        } else if (m == Mode.RGB_ALPHA) {
            byte[] random = new byte[w * h * 4];
            new Random().nextBytes(random);
            return fromDirectIMAGE(random, w, h);
        }
        throw new IllegalArgumentException();
    }
}
