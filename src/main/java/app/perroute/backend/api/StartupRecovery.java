package app.perroute.backend.api;

/** Invoked before loading saved state or starting the desktop UI. */
public interface StartupRecovery {
    void recover() throws Exception;
    void runGuard(Long parentPid) throws Exception;
}
