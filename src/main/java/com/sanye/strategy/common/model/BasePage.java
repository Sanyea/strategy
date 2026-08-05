package com.sanye.strategy.common.model;
import java.util.ArrayList;
import java.util.List;

public class BasePage<T> implements IBasePage<T> {

    protected List<T> records = new ArrayList<>();
    protected long total;
    protected long size = 10L;
    protected long current = 1L;

    public BasePage() {
    }

    public BasePage(long current, long size) {
        this.current = current;
        this.size = size;
    }

    public BasePage(long current, long size, long total) {
        this.current = current;
        this.size = size;
        this.total = total;
    }

    @Override
    public List<T> getRecords() {
        return records;
    }

    @Override
    public IBasePage<T> setRecords(List<T> records) {
        this.records = records;
        return this;
    }

    @Override
    public long getTotal() {
        return total;
    }

    @Override
    public IBasePage<T> setTotal(long total) {
        this.total = total;
        return this;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public IBasePage<T> setSize(long size) {
        this.size = size;
        return this;
    }

    @Override
    public long getCurrent() {
        return current;
    }

    @Override
    public IBasePage<T> setCurrent(long current) {
        this.current = current;
        return this;
    }
}