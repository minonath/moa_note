package mino.moa.note;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class MainActivity extends AppCompatActivity {
    private final int color_erase = Color.parseColor("#DDDDDD");
    private final int color_back = Color.parseColor("#000000");
    private final int color_box = Color.parseColor("#00DD00");
    private final int color_quick = Color.parseColor("#DD0000");
    private final int color_front = Color.parseColor("#0000DD");

    private TextView widget_previous, widget_next;
    private TextView widget_full_half, widget_view_edit, widget_lock_free;
    private TextView widget_minus, widget_plus, widget_delete, widget_backspace;
    private TextView widget_up, widget_down, widget_left, widget_right, widget_newline;
    private ImageView widget_image;
    private EditText widget_text;

    public static final String INSTRUCTION = "instruction";
    private SharedPreferences preferences;
    private static final String PREFERENCE_FULL_HALF = "full";
    private static final String PREFERENCE_VIEW_EDIT = "view";
    private static final String PREFERENCE_LOCK_FREE = "lock";
    private static final String PREFERENCE_WIDTH = "width";
    private static final String PREFERENCE_HEIGHT = "height";
    private boolean mode_full, mode_view, mode_lock;
    private int image_width, image_height;
    private String file_name;
    private int file_index;
    private NoteData file_current;
    private Map<Integer, NoteData> file_data;
    private Bitmap file_bitmap;
    private Canvas file_canvas;
    private Canvas image_canvas;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initialVariable();
        initialImageSize(); // 延迟获取图片组件尺寸，之后更新图片组件
        initialButtonText();
        initialButtonFunction_FULL_VIEW_LOCK();
        initialImageFunction();
        initialButtonFunction_MOVE();
        initialButtonFunction_ACTION();
        initialButtonFunction_TEXT();
    }

    private void initialVariable() {
        widget_previous = findViewById(R.id.top2);
        widget_next = findViewById(R.id.top3);
        widget_full_half = findViewById(R.id.middle1);
        widget_view_edit = findViewById(R.id.middle3);
        widget_lock_free = findViewById(R.id.middle4);
        widget_minus = findViewById(R.id.middle5);
        widget_plus = findViewById(R.id.middle6);
        widget_delete = findViewById(R.id.bottom5);
        widget_backspace = findViewById(R.id.bottom6);
        widget_up = findViewById(R.id.middle2);
        widget_down = findViewById(R.id.bottom2);
        widget_left = findViewById(R.id.bottom1);
        widget_right = findViewById(R.id.bottom3);
        widget_newline = findViewById(R.id.bottom4);
        widget_image = findViewById(R.id.image);
        widget_text = findViewById(R.id.top1);
        preferences = getSharedPreferences("moa_note", MODE_PRIVATE);
        mode_full = preferences.getBoolean(PREFERENCE_FULL_HALF, true);
        mode_view = preferences.getBoolean(PREFERENCE_VIEW_EDIT, true);
        mode_lock = preferences.getBoolean(PREFERENCE_LOCK_FREE, true);
        image_width = preferences.getInt(PREFERENCE_WIDTH, 0);
        image_height = preferences.getInt(PREFERENCE_HEIGHT, 0);
    }

    private void initialImageSize() {
        widget_image.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        /* if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) */
                        widget_image.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        image_width = widget_image.getWidth();
                        image_height = widget_image.getHeight();
                        SharedPreferences.Editor edit = preferences.edit();
                        edit.putInt(PREFERENCE_WIDTH, image_width);
                        edit.putInt(PREFERENCE_HEIGHT, image_height);
                        edit.apply();
                        int size = image_width < image_height ? image_width / 16 : image_height / 16;
                        widget_previous.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_next.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_full_half.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_view_edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_lock_free.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_minus.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_plus.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_delete.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_backspace.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_up.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_down.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_left.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_right.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        widget_newline.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                        updateImageViewExclusive();
                    }
                });
    }

    private void initialButtonText() {
        widget_previous.setText(R.string.previous);
        widget_next.setText(R.string.next);
        widget_full_half.setText(mode_full ? R.string.full : R.string.half);
        widget_view_edit.setText(mode_view ? R.string.view : R.string.edit);
        widget_lock_free.setText(mode_lock ? R.string.lock : R.string.free);
        widget_minus.setText(R.string.minus);
        widget_plus.setText(R.string.plus);
        widget_delete.setText(R.string.delete);
        widget_backspace.setText(R.string.backspace);
        widget_up.setText(R.string.up);
        widget_down.setText(R.string.down);
        widget_left.setText(R.string.left);
        widget_right.setText(R.string.right);
        widget_newline.setText(R.string.newline);
    }

    private void updateButtonFunctionEnable() {
        widget_text.setEnabled(mode_view);
        widget_minus.setEnabled(!mode_lock /* && mode_view */);
        widget_plus.setEnabled(!mode_lock /* && mode_view */);
        widget_delete.setEnabled(!mode_lock);
        widget_backspace.setEnabled(!mode_lock);
    }

    private void initialButtonFunction_FULL_VIEW_LOCK() {
        updateButtonFunctionEnable();
        widget_full_half.setOnClickListener(view -> {
            mode_full = !mode_full;
            ((TextView) view).setText(mode_full ? R.string.full : R.string.half);
            SharedPreferences.Editor edit = preferences.edit();
            edit.putBoolean(PREFERENCE_FULL_HALF, mode_full);
            edit.apply();
        });
        widget_view_edit.setOnClickListener(view -> {
            mode_view = !mode_view;
            ((TextView) view).setText(mode_view ? R.string.view : R.string.edit);
            updateButtonFunctionEnable();
            updateImageView();
            SharedPreferences.Editor edit = preferences.edit();
            edit.putBoolean(PREFERENCE_VIEW_EDIT, mode_view);
            edit.apply();
        });
        widget_lock_free.setOnClickListener(view -> {
            mode_lock = !mode_lock;
            ((TextView) view).setText(mode_lock ? R.string.lock : R.string.free);
            updateButtonFunctionEnable();
            SharedPreferences.Editor edit = preferences.edit();
            edit.putBoolean(PREFERENCE_LOCK_FREE, mode_lock);
            edit.apply();
        });
    }

    private float touch_x, touch_y, touch_length;
    private boolean touch_keep;
    private long time_stamp;
    private int point_count;

    private void imageFunctionActionDown1(MotionEvent event) {
        time_stamp = System.currentTimeMillis();
        int index = event.findPointerIndex(event.getPointerId(event.getActionIndex()));
        touch_x = event.getX(index);
        touch_y = event.getY(index);
        touch_length = 0;
    }

    private void imageFunctionActionDown2(MotionEvent event) {
        time_stamp = System.currentTimeMillis();
        float x1 = event.getX(0);
        float y1 = event.getY(0);
        float x2 = event.getX(1);
        float y2 = event.getY(1);
        touch_x = (x1 + x2) / 2;
        touch_y = (y1 + y2) / 2;
        touch_length = (float) Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2));
    }

    private void imageFunctionActionUp0(MotionEvent event) {
        int index = event.findPointerIndex(event.getPointerId(event.getActionIndex()));
        float ix = event.getX(index);
        float iy = event.getY(index);
        if (touch_keep) {
            touch_keep = false;
            return;
        }
        if (mode_view) {
            file_current.left = file_current.left + ix - touch_x;
            file_current.top = file_current.top + iy - touch_y;
            updateImageView();
        } else {
            updateImageViewDraw(touch_x, touch_y, ix, iy);
        }
        touch_x = ix;
        touch_y = iy;
    }

    private void imageFunctionActionUp1(MotionEvent event) {
        float x1 = event.getX(0);
        float y1 = event.getY(0);
        float x2 = event.getX(1);
        float y2 = event.getY(1);
        float cx = (x1 + x2) / 2;
        float cy = (y1 + y2) / 2;
        float length = (float) Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2));

        float target = file_current.scale * length / touch_length;
        if (target > 32) {
            file_current.left = cx - (touch_x - file_current.left) * 32 / file_current.scale;
            file_current.top = cy - (touch_y - file_current.top) * 32 / file_current.scale;
            file_current.scale = 32;
        } else if (target < 0.2) {
            file_current.left = cx - (touch_x - file_current.left) * 0.25f / file_current.scale;
            file_current.top = cy - (touch_y - file_current.top) * 0.25f / file_current.scale;
            file_current.scale = 0.25f;
        } else {
            file_current.left = cx - (touch_x - file_current.left) * length / touch_length;
            file_current.top = cy - (touch_y - file_current.top) * length / touch_length;
            file_current.scale = file_current.scale * length / touch_length;
        }
        updateImageView();

        int index = event.findPointerIndex(event.getPointerId(event.getActionIndex()));
        if (index == 0) {
            touch_x = x2;
            touch_y = y2;
        } else {
            touch_x = x1;
            touch_y = y1;
        }
        touch_length = length;
    }

    private void imageFunctionActionUp2(MotionEvent event) {
        float x1 = event.getX(0);
        float y1 = event.getY(0);
        float x2 = event.getX(1);
        float y2 = event.getY(1);
        float cx = (x1 + x2) / 2;
        float cy = (y1 + y2) / 2;
        float length = (float) Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2));

        float target = file_current.scale * length / touch_length;
        if (target > 32) {
            file_current.left = cx - (touch_x - file_current.left) * 32 / file_current.scale;
            file_current.top = cy - (touch_y - file_current.top) * 32 / file_current.scale;
            file_current.scale = 32;
        } else if (target < 0.2) {
            file_current.left = cx - (touch_x - file_current.left) * 0.25f / file_current.scale;
            file_current.top = cy - (touch_y - file_current.top) * 0.25f / file_current.scale;
            file_current.scale = 0.25f;
        } else {
            file_current.left = cx - (touch_x - file_current.left) * length / touch_length;
            file_current.top = cy - (touch_y - file_current.top) * length / touch_length;
            file_current.scale = file_current.scale * length / touch_length;
        }
        updateImageView();

        touch_x = cx;
        touch_y = cy;
        touch_length = length;
    }

    private void imageFunctionActionMove1(MotionEvent event) {
        if (touch_keep) {
            return;
        }
        int index = event.findPointerIndex(event.getPointerId(event.getActionIndex()));
        float ix = event.getX(index);
        float iy = event.getY(index);
        if (ix != touch_x || iy != touch_y) {
            if (mode_view) {
                file_current.left = file_current.left + ix - touch_x;
                file_current.top = file_current.top + iy - touch_y;
                updateImageView();
            } else {
                updateImageViewDraw(touch_x, touch_y, ix, iy);
            }
            touch_x = ix;
            touch_y = iy;
        }
        touch_length = 0;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initialImageFunction() {
        widget_image.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    point_count = 1;
                    imageFunctionActionDown1(event);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    point_count += 1;
                    if (point_count == 2) {
                        touch_keep = true;
                        imageFunctionActionDown2(event);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    point_count -= 1;
                    if (point_count == 2) {
                        imageFunctionActionUp2(event);
                    } else if (point_count == 1) {
                        imageFunctionActionUp1(event);
                    } else if (point_count == 0) {
                        imageFunctionActionUp0(event);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    long current = System.currentTimeMillis();
                    if (current - time_stamp > 32) { // 31 fps
                        time_stamp = current;
                        if (point_count == 2) {
                            imageFunctionActionUp2(event);
                        } else if (point_count == 1) {
                            imageFunctionActionMove1(event);
                        }
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private void arrowMoveRight(float full) {
        float step = image_width * file_current.rate * full;
        if (file_current.x + step > W) {
            if (arrowMoveDown(full)) {
                file_current.left = file_current.left + file_current.x * file_current.scale;
                file_current.x = 0;
            }
        } else {
            file_current.left = file_current.left - step * file_current.scale;
            file_current.x = file_current.x + step;
        }
    }

    private boolean arrowMoveDown(float full) {
        float step = image_height * file_current.rate * full;
        if (file_current.y + step > H) {
            return false;
        } else {
            file_current.top = file_current.top - step * file_current.scale;
            file_current.y = file_current.y + step;
            return true;
        }
    }

    private void arrowMoveLeft(float full) {
        if (file_current.x == 0) {
            if (arrowMoveUp(full)) {
                arrowGoLast(full);
            }
        } else {
            float step = image_width * file_current.rate * full;
            if (file_current.x - step < 0) {
                file_current.left = file_current.left + file_current.x * file_current.scale;
                file_current.x = 0;
            } else {
                file_current.left = file_current.left + step * file_current.scale;
                file_current.x = file_current.x - step;
            }
        }
    }

    private boolean arrowMoveUp(float full) {
        float step = image_height * file_current.rate * full;
        if (file_current.y - step < 0) {
            file_current.top = file_current.top + file_current.y * file_current.scale;
            file_current.y = 0;
            return false;
        } else {
            file_current.top = file_current.top + step * file_current.scale;
            file_current.y = file_current.y - step;
            return true;
        }
    }

    private void arrowMoveNewLine(float full) {
        if (arrowMoveDown(full)) {
            file_current.left = file_current.left + file_current.x * file_current.scale;
            file_current.x = 0;
        }
    }

    private void arrowGoLast(float full) {
        float k = image_width * file_current.rate * full;
        float target = (int) (W / k) * k;
        float step = target - file_current.x;
        file_current.x = target;
        file_current.left = file_current.left - step * file_current.scale;
    }

    private static final int DELAY_STEP = 100;
    private static final int DELAY_SLOW = 500;

    final Handler handler = new Handler(Looper.myLooper());

    final Runnable run_left = new Runnable() {
        @Override
        public void run() {
            arrowMoveLeft(mode_full ? 1 : 0.5f);
            updateImageView();
            handler.postDelayed(this, DELAY_STEP);
        }
    };

    final Runnable run_up = new Runnable() {
        @Override
        public void run() {
            arrowMoveUp(mode_full ? 1 : 0.5f);
            updateImageView();
            handler.postDelayed(this, DELAY_STEP);
        }
    };

    final Runnable run_right = new Runnable() {
        @Override
        public void run() {
            arrowMoveRight(mode_full ? 1 : 0.5f);
            updateImageView();
            handler.postDelayed(this, DELAY_STEP);
        }
    };

    final Runnable run_down = new Runnable() {
        @Override
        public void run() {
            arrowMoveDown(mode_full ? 1 : 0.5f);
            updateImageView();
            handler.postDelayed(this, DELAY_STEP);
        }
    };

    final Runnable run_newline = new Runnable() {
        @Override
        public void run() {
            arrowMoveNewLine(mode_full ? 1 : 0.5f);
            updateImageView();
            handler.postDelayed(this, DELAY_STEP);
        }
    };

    final Runnable run_backspace = new Runnable() {
        @Override
        public void run() {
            int lx = Math.min(W, (int) (file_current.x + image_width * file_current.rate));
            int ly = Math.min(H, (int) (file_current.y + image_height * file_current.rate));
            for (int x = (int) file_current.x; x < lx; x++) {
                for (int y = (int) file_current.y; y < ly; y++) {
                    file_bitmap.setPixel(x, y, Color.TRANSPARENT);
                }
            }
            file_canvas.setBitmap(file_bitmap);
            arrowMoveLeft(mode_full ? 1 : 0.5f);
            updateImageView();
            handler.postDelayed(this, DELAY_STEP);
        }
    };

    final Runnable run_minus = new Runnable() {
        @Override
        public void run() {
            file_current.step += 1;
            file_current.rate = (float) Math.sqrt(W * H /
                    file_current.step / file_current.step / image_width / image_height);
            updateImageView();
            handler.postDelayed(this, DELAY_STEP);
        }
    };

    final Runnable run_plus = new Runnable() {
        @Override
        public void run() {
            if (file_current.step > 1) {
                file_current.step -= 1;
                file_current.rate = (float) Math.sqrt(W * H /
                        file_current.step / file_current.step / image_width / image_height);
                updateImageView();
                handler.postDelayed(this, DELAY_STEP);
            }
        }
    };

    final Runnable run_next = new Runnable() {
        @Override
        public void run() {
            saveFileData();
            file_index += 1;
            loadFileData(file_name, file_index);
            file_current.delay(image_width, image_height, W, H);
            updateImageView();
            widget_text.clearFocus();
            handler.postDelayed(this, DELAY_SLOW);
        }
    };

    final Runnable run_previous = new Runnable() {
        @Override
        public void run() {
            saveFileData();
            if (file_index > 1) {
                file_index -= 1;
            } else {
                file_index = file_data.keySet().stream()
                        .max(Comparator.comparingInt(a -> a)).orElse(1);
            }
            loadFileData(file_name, file_index);
            file_current.delay(image_width, image_height, W, H);
            updateImageView();
            widget_text.clearFocus();
            handler.postDelayed(this, DELAY_SLOW);
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    private void initialButtonFunction_MOVE() {
        widget_left.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    run_left.run();
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(run_left);
                    return true;
            }
            return false;
        });
        widget_down.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    run_down.run();
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(run_down);
                    return true;
            }
            return false;
        });
        widget_right.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    run_right.run();
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(run_right);
                    return true;
            }
            return false;
        });
        widget_up.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    run_up.run();
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(run_up);
                    return true;
            }
            return false;
        });
        widget_newline.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    run_newline.run();
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(run_newline);
                    return true;
            }
            return false;
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initialButtonFunction_ACTION() {
        widget_delete.setOnClickListener(view -> {
            int lx = Math.min(W, (int) (file_current.x + image_width * file_current.rate));
            int ly = Math.min(H, (int) (file_current.y + image_height * file_current.rate));
            for (int x = (int) file_current.x; x < lx; x++) {
                for (int y = (int) file_current.y; y < ly; y++) {
                    file_bitmap.setPixel(x, y, Color.TRANSPARENT);
                }
            }
            file_canvas.setBitmap(file_bitmap);
            updateImageView();
            mode_lock = true;
            widget_lock_free.setText(R.string.lock);
            updateButtonFunctionEnable();
            SharedPreferences.Editor edit = preferences.edit();
            edit.putBoolean(PREFERENCE_LOCK_FREE, true);
            edit.apply();
        });
        widget_backspace.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    run_backspace.run();
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(run_backspace);
                    return true;
            }
            return false;
        });
        widget_minus.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    run_minus.run();
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(run_minus);
                    return true;
            }
            return false;
        });
        widget_plus.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    run_plus.run();
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(run_plus);
                    return true;
            }
            return false;
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initialButtonFunction_TEXT() {
        widget_text.setOnEditorActionListener((view, id, event) -> {
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_SEND) {
                String name = legalName(widget_text.getText().toString());
                if (!(file_name + "-" + file_index).equals(name)) {
                    saveFileData();
                }
                int index = name.lastIndexOf("-");
                if (index == -1) {
                    loadFileData(name, 0);
                } else {
                    try {
                        loadFileData(name.substring(0, index),
                                Integer.parseInt(name.substring(index + 1)));
                    } catch (NumberFormatException exception) {
                        loadFileData(name, 0);
                    }
                }
                file_current.delay(image_width, image_height, W, H);
                updateImageView();
                widget_text.clearFocus();
                return true;
            }
            return false;
        });
        widget_next.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    run_next.run();
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(run_next);
                    return true;
            }
            return false;
        });
        widget_previous.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    run_previous.run();
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(run_previous);
                    return true;
            }
            return false;
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveFileData();
        File cache = new File(getExternalFilesDir(null), "cache");
        try (FileOutputStream fos = new FileOutputStream(cache)) {
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(file_name);
            oos.writeInt(file_index);
            oos.close();
        } catch (IOException ignored) {
        }
    }

    private static final int W = 297 * 3;
    private static final int H = 210 * 6;

    @Override
    protected void onResume() {
        super.onResume();
        try (FileInputStream fis = new FileInputStream(
                new File(getExternalFilesDir(null), "cache"))) {
            ObjectInputStream ois = new ObjectInputStream(fis);
            loadFileData((String) ois.readObject(), ois.readInt());
            ois.close();
        } catch (IOException | ClassNotFoundException exception) {
            loadFileData(INSTRUCTION, 0);
        }
        updateImageViewExclusive(); // 与 initialImageSize 内的函数排斥
    }

    private byte[] gzipInput(byte[] brief) {
        /* return brief; */
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(brief);
            GZIPInputStream gzipInputStream = new GZIPInputStream(inputStream);
            byte[] cache = new byte[1024];
            int length;
            while ((length = gzipInputStream.read(cache)) > 0) {
                outputStream.write(cache, 0, length);
            }
            gzipInputStream.close();
            inputStream.close();
            return outputStream.toByteArray();
        } catch (IOException ignored) {
            return new byte[0];
        }
    }

    private byte[] gzipOutput(byte[] origin) {
        /* return origin; */
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream);
            gzipOutputStream.write(origin);
            gzipOutputStream.finish();
            gzipOutputStream.close();
            return outputStream.toByteArray();
        } catch (IOException ignored) {
            return new byte[0];
        }
    }

    private void loadFileData(String name, int index) {
        file_name = name.length() == 0 ? getTimeAsName() : name;
        file_data = new HashMap<>();
        file_bitmap = getDefaultBitmap();

        try (FileInputStream fis = new FileInputStream(
                new File(getExternalFilesDir(null), file_name + "-0"))) {
            ObjectInputStream ois = new ObjectInputStream(fis);
            int file_size = ois.readInt();
            file_index = ois.readInt();
            for (int i = 0; i < file_size; i++) {
                file_data.put(ois.readInt(), (NoteData) ois.readObject());
            }
            ois.close();
        } catch (IOException | ClassNotFoundException exception) {
            file_index = 1;
        }

        if (index > 0) {
            file_index = index;
        }

        if (file_data.containsKey(file_index)) {
            file_current = file_data.get(file_index);
        } else {
            file_current = new NoteData();
            file_data.put(file_index, file_current);
        }
        if (file_name.equals(INSTRUCTION)) {
            try {
                file_bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(SimplePNG.fromPNG(
                        getAssets().open(INSTRUCTION + ".png")).getInnerData()));
            } catch (IOException | DataFormatException ignored) {
            }
        } else {
            try (FileInputStream fis = new FileInputStream(
                    new File(getExternalFilesDir(null),
                            file_name + "-" + file_index + ".png"))) {
                byte[] data = SimplePNG.fromPNG(fis).getInnerData();
                for (int i = 0; i < data.length; i++) {
                    data[i] = (byte) ~data[i];
                }
                file_bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data));
            } catch (IOException | DataFormatException ignored) {
            }
        }
        file_canvas = new Canvas(file_bitmap);
        widget_text.setText(String.format("%s-%s", file_name, file_index));
    }

    private Bitmap getDefaultBitmap() {
        return Bitmap.createBitmap(W, H, Bitmap.Config.ALPHA_8);
    }

    private String getTimeAsName() {
        return new SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                .format(Calendar.getInstance().getTime());
    }

    private void saveFileData() {
        if (file_name.equals(INSTRUCTION)) {
            return;
        }
        if (currentImageNotEmpty()) {
            ByteBuffer buffer = ByteBuffer.allocate(file_bitmap.getByteCount());
            file_bitmap.copyPixelsToBuffer(buffer);
            byte[] data = buffer.array();
            try (FileOutputStream png = new FileOutputStream(
                    new File(getExternalFilesDir(null),
                            file_name + "-" + file_index + ".png"))) {
                for (int i = 0; i < data.length; i ++) {
                    data[i] = (byte) ~data[i];
                }
                SimplePNG.fromDirectIMAGE(data, W, H).toPNG(png);
            } catch (IOException ignored) {
            }
        } else {
            file_data.remove(file_index);
            new File(getExternalFilesDir(null), file_name + "-" + file_index + ".png").delete();
        }
        try (FileOutputStream fos = new FileOutputStream(
                new File(getExternalFilesDir(null), file_name + "-0"))) {
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeInt(file_data.size());
            oos.writeInt(file_index);
            for (Map.Entry<Integer, NoteData> entry : file_data.entrySet()) {
                oos.writeInt(entry.getKey());
                oos.writeObject(entry.getValue());
            }
            oos.close();
        } catch (IOException ignored) {
        }
    }

    private boolean currentImageNotEmpty() {
        ByteBuffer buffer = ByteBuffer.allocate(file_bitmap.getByteCount());
        file_bitmap.copyPixelsToBuffer(buffer);
        byte[] data = buffer.array();
        for (byte datum : data) {
            if (datum != 0) {
                return true;
            }
        }
        return false;
    }

    private volatile boolean initial_once = false;

    private synchronized void updateImageViewExclusive() {
        if (initial_once) {
            initial_once = false;
            Bitmap image_bitmap = Bitmap.createBitmap(image_width, image_height, Bitmap.Config.ARGB_8888);
            widget_image.setImageBitmap(image_bitmap);
            image_canvas = new Canvas(image_bitmap);
            file_current.delay(image_width, image_height, W, H);
            updateImageView();
        } else {
            initial_once = true;
        }
    }

    private void updateImageView() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setFilterBitmap(true);

        // 擦除图片
        paint.setColor(color_erase);
        image_canvas.drawRect(0, 0, image_width, image_height, paint);

        int left = (int) (file_current.left > 0 ? 0 : -file_current.left / file_current.scale);
        int top = (int) (file_current.top > 0 ? 0 : -file_current.top / file_current.scale);
        int width = Math.min((int) ((image_width - file_current.left) / file_current.scale), W) - left;
        int height = Math.min((int) ((image_height - file_current.top) / file_current.scale), H) - top;

        if (width > 0) {
            if (height > 0) {
                // 绘制底层图片
                Bitmap cropped = Bitmap.createBitmap(file_bitmap, left, top, width, height);
                paint.setColor(color_back);
                float max_left = Math.max(0, file_current.left);
                float max_top = Math.max(0, file_current.top);
                float reverse = 1 / file_current.scale;
                image_canvas.scale(file_current.scale, file_current.scale, max_left, max_top);
                image_canvas.drawBitmap(
                        cropped, Math.max(0, file_current.left), Math.max(0, file_current.top), paint);
                image_canvas.scale(reverse, reverse, max_left, max_top);

                // 绘制内框
                paint.setStyle(Paint.Style.STROKE);
                float box_l = file_current.left + file_current.x * file_current.scale;
                float box_t = file_current.top + file_current.y * file_current.scale;
                float box_r = box_l + (int) (image_width * file_current.rate) * file_current.scale;
                float box_b = box_t + (int) (image_height * file_current.rate) * file_current.scale;
                paint.setColor(color_box);
                image_canvas.drawRect(box_l, box_t, box_r, box_b, paint);
            } else {
                file_current.top = top == 0 ? image_height : -H * file_current.scale;
            }
        } else {
            if (height <= 0) {
                file_current.top = top == 0 ? image_height : -H * file_current.scale;
            }
            file_current.left = left == 0 ? image_width : -W * file_current.scale;
        }

        // 绘制外框
        paint.setColor(color_back);
        image_canvas.drawRect(file_current.left - 3, file_current.top - 3,
                (float) Math.ceil(file_current.left + W * file_current.scale + 3),
                (float) Math.ceil(file_current.top + H * file_current.scale + 3), paint);
        paint.setStyle(Paint.Style.FILL);

        if (!mode_view) {
            int max_width = W - (int) (file_current.x);
            int max_height = H - (int) (file_current.y);
            int step_width = (int) (image_width * file_current.rate);
            int step_height = (int) (image_height * file_current.rate);
            int scale_width, scale_height, crop_width, crop_height;
            if (max_width > step_width) {
                crop_width = step_width;
                scale_width = image_width;
            } else {
                crop_width = max_width;
                scale_width = image_width * max_width / step_width;
            }
            if (max_height > step_height) {
                crop_height = step_height;
                scale_height = image_height;
            } else {
                crop_height = max_height;
                scale_height = image_height * max_height / step_height;
            }
            paint.setColor(color_front);
            image_canvas.drawBitmap(file_bitmap, new Rect((int) file_current.x, (int) file_current.y,
                            (int) file_current.x + crop_width,
                            (int) file_current.y + crop_height),
                    new Rect(0, 0, scale_width, scale_height), paint);
        }
        widget_image.postInvalidate();
    }

    private void updateImageViewDraw(float x1, float y1, float x2, float y2) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color_back);
        file_canvas.drawLine(
                file_current.x + x1 * file_current.rate,
                file_current.y + y1 * file_current.rate,
                file_current.x + x2 * file_current.rate,
                file_current.y + y2 * file_current.rate, paint);

        paint.setColor(color_quick);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(file_current.scale);
        paint.setAlpha(128);

        float box_l = file_current.left + file_current.x * file_current.scale;
        float box_t = file_current.top + file_current.y * file_current.scale;
        image_canvas.drawLine(
                box_l + x1 * file_current.rate * file_current.scale,
                box_t + y1 * file_current.rate * file_current.scale,
                box_l + x2 * file_current.rate * file_current.scale,
                box_t + y2 * file_current.rate * file_current.scale, paint);
        paint.setColor(color_front);
        paint.setStrokeWidth(1 / file_current.rate);

        image_canvas.drawLine(x1, y1, x2, y2, paint);
        widget_image.postInvalidate();
    }

    private String legalName(String string) {
        String regex = "[/\\\\:*?\"<>|]";
        return string.replaceAll(regex, "");
    }
}
