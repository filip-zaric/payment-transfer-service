package com.example.payments.persistence;

import com.example.payments.domain.Currency;
import java.util.UUID;

public record Account(UUID id, String ownerId, long balanceMinor, Currency currency) {}
