package com.papaping.gui;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * A single slider that scans the color spectrum (hue), writing a fully-saturated color back
 * through a {@link Sink}. One slider per health tier on the Health Colors tab — ideal here since
 * red→yellow→green all live on the hue wheel.
 */
public class HueSlider extends SliderWidget {

    public interface Sink {
        int get();
        void set(int rgb);
    }

    private final Sink sink;
    private final Runnable onChange;

    public HueSlider(int x, int y, int w, int h, Sink sink, Runnable onChange) {
        super(x, y, w, h, Text.literal(""), hueOf(sink.get()));
        this.sink = sink;
        this.onChange = onChange;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Text.literal("#" + String.format("%06X", sink.get() & 0xFFFFFF)));
    }

    @Override
    protected void applyValue() {
        sink.set(hsvToRgb(this.value));
        updateMessage();
        if (onChange != null) onChange.run();
    }

    /** Hue (0–1) of an RGB color, for positioning the slider from a saved color. */
    private static double hueOf(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double d = max - min;
        if (d == 0) return 0;
        double hue;
        if (max == r) hue = ((g - b) / d) % 6;
        else if (max == g) hue = (b - r) / d + 2;
        else hue = (r - g) / d + 4;
        hue /= 6;
        if (hue < 0) hue += 1;
        return hue;
    }

    /** Fully-saturated, full-value RGB for a hue in 0–1. */
    private static int hsvToRgb(double hue) {
        double h = (hue % 1.0) * 6.0;
        int i = (int) Math.floor(h);
        double f = h - i;
        int q = (int) Math.round((1 - f) * 255);
        int t = (int) Math.round(f * 255);
        int r, g, b;
        switch (i % 6) {
            case 0 -> { r = 255; g = t;   b = 0;   }
            case 1 -> { r = q;   g = 255; b = 0;   }
            case 2 -> { r = 0;   g = 255; b = t;   }
            case 3 -> { r = 0;   g = q;   b = 255; }
            case 4 -> { r = t;   g = 0;   b = 255; }
            default -> { r = 255; g = 0;   b = q;   }
        }
        return (r << 16) | (g << 8) | b;
    }
}
