package com.eb.lib
public class PipelineEnvironmentException extends Exception {
    public PipelineEnvironmentException () {

    }

    public PipelineEnvironmentException (String message) {
        super ("[ERROR] ${message}");
    }

    public PipelineEnvironmentException (Throwable cause) {
        super (cause);
    }

    public PipelineEnvironmentException (String message, Throwable cause) {
        super ("[ERROR] ${message}", cause);
    }
}
