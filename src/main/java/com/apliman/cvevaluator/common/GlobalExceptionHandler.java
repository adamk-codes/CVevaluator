package com.apliman.cvevaluator.common;

import com.apliman.cvevaluator.job.InvalidJobRequirementsException;
import com.apliman.cvevaluator.job.JobNotFoundException;
import com.apliman.cvevaluator.storage.InvalidUploadException;
import com.apliman.cvevaluator.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleJobNotFound(JobNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(404, ex.getMessage()));
    }

    // Same shape as the InvalidUploadException handler below and for the same
    // reason: every message is authored in JobRequirementsValidator and repeats
    // only what the client sent, so it is safe to return as-is. It is a distinct
    // type rather than an IllegalArgumentException so that "the requirements
    // list broke a rule" cannot be widened by accident into "some argument
    // somewhere was wrong".
    @ExceptionHandler(InvalidJobRequirementsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequirements(InvalidJobRequirementsException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, message));
    }

    // for the HeaderCurrentUserProvider missing id in header or using a wrong type of id
    @ExceptionHandler({ IllegalStateException.class, IllegalArgumentException.class })
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, "Malformed request body"));
    }

    // Every InvalidUploadException message is authored in the storage package and
    // contains no filesystem path, so it is safe to return as-is.
    @ExceptionHandler(InvalidUploadException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUpload(InvalidUploadException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, ex.getMessage()));
    }

    // Thrown during multipart resolution, before the controller method is
    // entered - spring.servlet.multipart.max-file-size does the rejecting, not
    // application code. Message is fixed rather than ex.getMessage(), which
    // reports the raw byte counts and the resolver internals.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse(413, "File is too large. Maximum upload size is 10MB"));
    }

    // The request claimed multipart but could not be parsed - broken boundary,
    // truncated body. MaxUploadSizeExceededException is a subclass and is
    // matched by the more specific handler above regardless of declaration order.
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMalformedMultipart(MultipartException ex) {
        log.warn("Malformed multipart request", ex);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, "Malformed multipart request"));
    }

    // Fixed message: StorageException carries the absolute storage root, which the
    // client must never see. The real cause goes to the log.
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handleStorageFailure(StorageException ex) {
        log.error("Storage failure", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "Could not store file"));
    }
}
