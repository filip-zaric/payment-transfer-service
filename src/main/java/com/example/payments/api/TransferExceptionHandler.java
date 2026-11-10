package com.example.payments.api;

import com.example.payments.domain.ErrorCode;
import com.example.payments.domain.TransferException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

@Singleton
public class TransferExceptionHandler
    implements ExceptionHandler<TransferException, HttpResponse<ApiError>> {
  @Override
  public HttpResponse<ApiError> handle(HttpRequest request, TransferException exception) {
    var response =
        HttpResponse.<ApiError>status(HttpStatus.valueOf(exception.code().status()))
            .body(new ApiError(exception.code().name(), exception.code().message()));
    if (exception.code() == ErrorCode.SERVICE_UNAVAILABLE) response.header("Retry-After", "1");
    return response;
  }
}
