package vn.edu.exam.client.ui;

@FunctionalInterface
public interface UiDispatcher {
    void dispatch(Runnable update);
}
