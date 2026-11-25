package com.todollab.auth.service;

import com.todollab.auth.model.User;

import java.util.Optional;
import java.util.UUID;

/**
 * Extension point implemented by the invite module (Task 6) so invite-backed registrations
 * can auto-activate accounts and join boards atomically without tight coupling.
 */
public interface InviteAutoAcceptHook {

    boolean canAutoActivateOnRegister(String inviteToken, String normalizedEmail);

    Optional<UUID> acceptOnRegister(String inviteToken, User user);
}
