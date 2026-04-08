package com.subx.general.core.api.notification;

public interface INotifier {
    boolean addListener(IListener listener);
    boolean removeListener(IListener listener);
}
