package com.stockrescue.platform.security;

import java.util.UUID;

public interface SessionAccountStatus {
    boolean active(UUID user);
}
