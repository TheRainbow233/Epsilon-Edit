package com.github.epsilon.managers.impl;

import com.github.epsilon.holders.ConfigHolder;
import com.github.epsilon.utils.auth.MicrosoftAuth;
import com.github.epsilon.utils.auth.MicrosoftAuth.AuthResult;
import net.minecraft.client.User;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import com.github.epsilon.Constants;

import static com.github.epsilon.Constants.mc;

/**
 * 账号管理器 —— Session 账号增删改查、登录、持久化。
 */
public class AccountManager {

    private final List<Account> accounts = new CopyOnWriteArrayList<>();
    private Account currentAccount;

    public AccountManager() {
        var loaded = ConfigHolder.INSTANCE.loadAccounts();
        this.accounts.addAll(loaded);
        Constants.LOGGER.info("[AccountManager] Initialized with {} account(s)", loaded.size());
    }

    public List<Account> getAccounts() { return accounts; }
    public Account getCurrentAccount() { return currentAccount; }

    public Account addSessionAccount(String token) throws MicrosoftAuth.AuthException {
        AuthResult result = MicrosoftAuth.authenticate(token);

        for (Account a : accounts) {
            if (a.uuid != null && a.uuid.equalsIgnoreCase(result.uuid())) {
                a.token = token;
                ConfigHolder.INSTANCE.saveAccounts();
                return a;
            }
        }

        Account account = new Account(result.name(), result.uuid(), token,
                System.currentTimeMillis(), AccountType.SESSION);
        accounts.add(account);
        ConfigHolder.INSTANCE.saveAccounts();
        return account;
    }

    public void login(Account account) throws MicrosoftAuth.AuthException {
        switch (account.type) {
            case CRACKED -> {
                loginOffline(account);
                return;
            }
            case MICROSOFT -> {
                AuthResult result = MicrosoftAuth.authenticateMicrosoft(account.token);
                applyLoginResult(account, result, false);
            }
            case SESSION -> {
                AuthResult result = MicrosoftAuth.authenticate(account.token);
                applyLoginResult(account, result, true);
            }
        };
    }

    private void applyLoginResult(Account account, AuthResult result, boolean includeXuid) {
        UUID uuid = UUID.fromString(addDashes(result.uuid()));
        Optional<String> xuid = includeXuid
                ? Optional.of("00000000402b5328") : Optional.empty();
        User newUser = new User(result.name(), uuid, result.accessToken(),
                Optional.empty(), xuid);

        var mixin = (com.github.epsilon.mixins.MixinMinecraftSession) (Object) mc;
        mixin.epsilon$setUser(newUser);

        account.token = result.refreshToken();
        currentAccount = account;
        ConfigHolder.INSTANCE.saveAccounts();
    }

    public void remove(Account account) {
        accounts.remove(account);
        if (currentAccount == account) currentAccount = null;
        ConfigHolder.INSTANCE.saveAccounts();
    }

    // ── 离线登录 ──────────────────────────────────────────────────────────────

    public Account addOfflineAccount(String name) {
        if (name == null || name.isBlank()) return null;
        String uuidStr = UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        for (Account a : accounts) {
            if (a.uuid != null && a.uuid.equalsIgnoreCase(uuidStr)) return a;
        }
        Account account = new Account(name, uuidStr, "",
                System.currentTimeMillis(), AccountType.CRACKED);
        accounts.add(account);
        ConfigHolder.INSTANCE.saveAccounts();
        return account;
    }

    public void loginOffline(Account account) {
        UUID uuid = UUID.fromString(addDashes(account.uuid));
        User newUser = new User(account.name, uuid, "offline",
                Optional.empty(), Optional.empty());
        var mixin = (com.github.epsilon.mixins.MixinMinecraftSession) (Object) mc;
        mixin.epsilon$setUser(newUser);
        currentAccount = account;
    }

    // ── 微软浏览器 OAuth 登录 ────────────────────────────────────────────────

    public Account microsoftLogin() throws MicrosoftAuth.AuthException {
        MicrosoftAuth.AuthResult result = MicrosoftAuth.loginWithBrowser();
        for (Account a : accounts) {
            if (a.uuid != null && a.uuid.equalsIgnoreCase(result.uuid())) {
                a.token = result.refreshToken();
                ConfigHolder.INSTANCE.saveAccounts();
                return a;
            }
        }
        Account account = new Account(result.name(), result.uuid(), result.refreshToken(),
                System.currentTimeMillis(), AccountType.MICROSOFT);
        accounts.add(account);
        ConfigHolder.INSTANCE.saveAccounts();
        return account;
    }

    private static String addDashes(String uuid) {
        if (uuid.length() == 36) return uuid;
        return uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-"
                + uuid.substring(12, 16) + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20);
    }

    public enum AccountType { SESSION, MICROSOFT, CRACKED }

    public static class Account {
        public String name, uuid, token;
        public long addedAt;
        public AccountType type;

        public Account(String n, String u, String t, long at, AccountType ty) {
            name = n; uuid = u; token = t; addedAt = at; type = ty;
        }

        @Override
        public String toString() { return name + " [" + type + "]"; }
    }
}
