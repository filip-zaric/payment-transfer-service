package com.example.payments.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.security.token.Claims;
import io.micronaut.security.token.jwt.validator.GenericJwtClaimsValidator;
import jakarta.inject.Singleton;

/**
 * Micronaut validates signature, issuer, audience and expiry; additionally require exp to exist.
 */
@Singleton
public class RequiredClaimsValidator implements GenericJwtClaimsValidator<HttpRequest<?>> {
  @Override
  public boolean validate(Claims claims, HttpRequest<?> request) {
    Object subject = claims.get(Claims.SUBJECT);
    return subject instanceof String name
        && !name.isBlank()
        && name.length() <= 128
        && claims.get(Claims.EXPIRATION_TIME) != null;
  }
}
