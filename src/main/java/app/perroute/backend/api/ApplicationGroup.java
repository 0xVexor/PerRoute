package app.perroute.backend.api;

import java.util.List;

/** One picker choice and its observed exact executable identities, not routing authorization. */
public record ApplicationGroup(ApplicationDescriptor application, List<String> executables, String observationFingerprint) {
    public ApplicationGroup { executables = List.copyOf(executables); }
    public ApplicationGroup(ApplicationDescriptor application, List<String> executables) { this(application, executables, ""); }
}
