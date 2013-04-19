package org.jenkinsci.plugins.veracodescanner.exception;

public class VeracodeScannerException extends Exception {

	public VeracodeScannerException() {
	}

	public VeracodeScannerException(String message) {
		super(message);
	}

	public VeracodeScannerException(Throwable cause) {
		super(cause);
	}

	public VeracodeScannerException(String message, Throwable cause) {
		super(message, cause);
	}

}
