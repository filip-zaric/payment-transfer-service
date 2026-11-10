package com.example.payments.service;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.time.Clock;

@Factory
public class ClockFactory {
  @Singleton
  Clock clock() {
    return Clock.systemUTC();
  }
}
