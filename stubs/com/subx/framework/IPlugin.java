package com.subx.framework;

public interface IPlugin {
    String getId();
    void start() throws Exception;
    void stop() throws Exception;

    class Adapter implements IPlugin {
        @Override public String getId() { return null; }
        @Override public void start() throws Exception {}
        @Override public void stop() throws Exception {}
    }
}
