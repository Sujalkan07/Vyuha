package com.vyuha.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized exception handler returning structured JSON for API requests
 * and clean Thymeleaf error views for browser requests.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleValidation(MethodArgumentNotValidException ex,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Submitted input failed validation.", details, request, response, "error/error");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Object handleAccessDenied(AccessDeniedException ex,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to access this resource.", List.of(),
                request, response, "error/403");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNotFound(NoResourceFoundException ex,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "The requested page or resource could not be found.", List.of(),
                request, response, "error/error");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object handleMaxUploadSize(MaxUploadSizeExceededException ex,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "Uploaded file exceeds the maximum allowed size of 10 MB.", List.of(),
                request, response, "error/error");
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Object handleResponseStatus(ResponseStatusException ex,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String msg = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        String view = status == HttpStatus.FORBIDDEN ? "error/403" : "error/error";
        return respond(status, status.name(), msg, List.of(), request, response, view);
    }

    @ExceptionHandler(Exception.class)
    public Object handleUnexpected(Exception ex,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        log.error("Unhandled exception for request {}", request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected server error occurred. Please try again or contact an administrator.",
                List.of(), request, response, "error/error");
    }

    private Object respond(HttpStatus status,
                           String code,
                           String message,
                           List<String> details,
                           HttpServletRequest request,
                           HttpServletResponse response,
                           String viewName) {
        if (isApiRequest(request)) {
            return ResponseEntity.status(status).body(ApiResponse.error(code, message, details));
        }
        response.setStatus(status.value());
        ModelAndView mav = new ModelAndView(viewName);
        mav.setStatus(status);
        mav.addObject("statusCode", status.value());
        mav.addObject("errorTitle", status.getReasonPhrase());
        mav.addObject("errorMessage", message);
        mav.addObject("details", details);
        return mav;
    }

    public static boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)
                && !accept.contains(MediaType.TEXT_HTML_VALUE);
    }
}
