package com.github.epsilon.settings.impl;

import com.github.epsilon.assets.i18n.TranslateComponent;
import com.github.epsilon.settings.Setting;

import java.util.*;

/**
 * A multi-select setting for enum values, rendered as inline checkboxes.
 * Stores a {@link LinkedHashSet} of selected enum constants to preserve insertion order.
 */
public class MultiEnumSetting<E extends Enum<E>> extends Setting<Set<E>> {

    private final E[] constants;
    private final Map<E, TranslateComponent> translations = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public MultiEnumSetting(String name, Collection<E> defaultValue, Dependency dependency) {
        super(name, dependency, null);
        Class<E> enumClass = (Class<E>) defaultValue.iterator().next().getDeclaringClass();
        this.constants = enumClass.getEnumConstants();
        this.defaultValue = Set.copyOf(defaultValue);
        this.value = new LinkedHashSet<>(defaultValue);
    }

    @Override
    public void initTranslateComponent(TranslateComponent component) {
        super.initTranslateComponent(component);
        translations.clear();
        for (E m : constants) {
            translations.put(m, component.createChild(m.name().toLowerCase()));
        }
    }

    public String getTranslatedName(Enum<?> value) {
        @SuppressWarnings("unchecked")
        TranslateComponent comp = translations.get((E) value);
        return comp != null ? comp.getTranslatedName() : value.name();
    }

    public E[] getConstants() {
        return constants;
    }

    /**
     * Raw accessor for GUI code holding a wildcard reference.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Enum<?>[] getRawConstants(MultiEnumSetting<?> s) {
        return s.constants;
    }

    public boolean isSelected(E value) {
        return this.value.contains(value);
    }

    /**
     * Raw accessor for GUI code holding a wildcard reference.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean isSelectedRaw(MultiEnumSetting<?> s, Enum<?> value) {
        return s.value.contains(value);
    }

    public void toggle(E value) {
        Set<E> next = new LinkedHashSet<>(this.value);
        if (next.contains(value)) {
            next.remove(value);
        } else {
            next.add(value);
        }
        setValue(next);
    }

    /**
     * Raw accessor for GUI code holding a wildcard reference.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void toggleRaw(MultiEnumSetting<?> s, Enum<?> value) {
        Set<Enum<?>> next = new LinkedHashSet<>(s.value);
        if (next.contains(value)) {
            next.remove(value);
        } else {
            next.add(value);
        }
        ((MultiEnumSetting) s).setValueRaw(next);
    }

    public void selectOnly(E value) {
        setValue(Set.of(value));
    }

    public int size() {
        return this.value.size();
    }

    public boolean isEmpty() {
        return this.value.isEmpty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setValue(Set<E> value) {
        super.setValue(new LinkedHashSet<>(value));
    }

    /**
     * Internal: sets the value from raw enum set (used by config deserialization).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setValueRaw(Set<? extends Enum<?>> value) {
        this.value = new LinkedHashSet(value);
    }

    @Override
    public void reset() {
        this.value = new LinkedHashSet<>(this.defaultValue);
    }

}
