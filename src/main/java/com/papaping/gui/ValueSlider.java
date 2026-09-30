package com.papaping.gui;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** A labeled numeric slider mapping 0–1 onto [min, max], for the Settings tab. */
public class ValueSlider extends SliderWidget {

    private final double min, max;
    private final boolean integer;
    private final String label, unit;
    private final DoubleConsumer setter;
    private final Runnable onChange;

    public ValueSlider(int x, int y, int w, int h, double min, double max, boolean integer,
                       String label, String unit, DoubleSupplier getter, DoubleConsumer setter,
                       Runnable onChange) {
        super(x, y, w, h, Text.literal(""), clamp01((getter.getAsDouble() - min) / (max - min)));
        this.min = min;
        this.max = max;
        this.integer = integer;
        this.label = label;
        this.unit = unit;
        this.setter = setter;
        this.onChange = onChange;
        updateMessage();
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    private double current() {
        double v = min + this.value * (max - min);
        return integer ? Math.round(v) : Math.round(v * 10.0) / 10.0;
    }

    @Override
    protected void updateMessage() {
        double v = current();
        String num = integer ? String.valueOf((int) v) : String.format("%.1f", v);
        setMessage(Text.literal(label + ": §f" + num + unit));
    }

    @Override
    protected void applyValue() {
        setter.accept(current());
        updateMessage();
        if (onChange != null) onChange.run();
    }
}
