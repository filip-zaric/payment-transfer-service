package com.example.payments.api;

import com.example.payments.domain.TransferCommand;
import com.example.payments.domain.TransferReceipt;
import com.example.payments.service.TransferService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import java.util.UUID;

@Controller("/transfers")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
@Validated
public class TransferController {
  private final TransferService service;

  public TransferController(TransferService service) {
    this.service = service;
  }

  @Post
  public HttpResponse<TransferReceipt> transfer(
      Authentication authentication,
      @Header("Idempotency-Key") UUID key,
      @Body @Valid TransferRequest request) {
    var outcome =
        service.transfer(
            authentication.getName(),
            new TransferCommand(
                key,
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                request.currency()));
    return HttpResponse.<TransferReceipt>status(
            outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .header("Location", "/transfers/" + outcome.receipt().id())
        .header("Idempotency-Replayed", Boolean.toString(outcome.replayed()))
        .body(outcome.receipt());
  }

  @Get("/{id}")
  public TransferReceipt find(Authentication authentication, UUID id) {
    return service.find(authentication.getName(), id);
  }
}
