package mino.moa.note;

import java.io.Serializable;

class NoteData implements Serializable {
    float left, top;
    float scale;
    float step;
    float rate;
    float x, y;

    void delay(int image_width, int image_height, int note_width, int note_height) {
        if (step == 0) {
            step = 24;
            scale = 1.6f * image_width / note_width;
            rate = (float) Math.sqrt(note_width * note_height / step / step / image_width / image_height);
            left = image_width / 2.6f;
            top = image_height / 4.2f;
        }
    }
}
