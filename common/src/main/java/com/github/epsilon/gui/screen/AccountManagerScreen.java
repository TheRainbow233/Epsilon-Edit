package com.github.epsilon.gui.screen;

import com.github.epsilon.Constants;
import com.github.epsilon.graphics.LuminRenderSystem;
import com.github.epsilon.graphics.renderers.TextRenderer;
import com.github.epsilon.graphics.text.StaticFontLoader;
import com.github.epsilon.gui.lib.UiRect;
import com.github.epsilon.gui.lib.UiTree;
import com.github.epsilon.gui.lib.scene.UiLayer;
import com.github.epsilon.gui.lib.scene.UiScene;
import com.github.epsilon.gui.panel.view.settings.ClientSettingTextField;
import com.github.epsilon.gui.theme.EpsilonUiTheme;
import com.github.epsilon.assets.i18n.EpsilonTranslations;
import com.github.epsilon.gui.theme.MD3Theme;
import com.github.epsilon.managers.impl.AccountManager.Account;
import com.github.epsilon.managers.Managers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 账号管理界面 — Epsilon 渲染管线。
 */
public class AccountManagerScreen extends Screen {

    public static final AccountManagerScreen INSTANCE = new AccountManagerScreen();

    // ── 渲染 ──
    private UiScene scene;
    private LuminRenderSystem.LuminRenderTarget renderTarget;
    private TextRenderer textRenderer;

    // ── 输入 ──
    private ClientSettingTextField tokenInput;

    // ── 状态 ──
    private int selectedIndex = -1;
    private final List<UiRect> accountRowBounds = new ArrayList<>();
    private final Map<Integer, String> accountStatus = new HashMap<>();

    // ── 按钮 hover 进度（8 个按钮）──
    private final float[] buttonHovers = new float[8];
    private static final int BTN_PASTE = 0;
    private static final int BTN_ADD = 1;
    private static final int BTN_LOGIN = 2;
    private static final int BTN_DELETE = 3;
    private static final int BTN_ADD_CLIP = 4;
    private static final int BTN_DONE = 5;
    private static final int BTN_OFFLINE = 6;
    private static final int BTN_MS_LOGIN = 7;

    // ── 每帧计算的按钮 bounds（用于命中测试）──
    private final UiRect[] buttonBounds = new UiRect[8];

    // 输入框 bounds
    private UiRect inputBounds = new UiRect(0, 0, 0, 0);

    private AccountManagerScreen() {
        super(Component.literal(EpsilonTranslations.Gui.ACCOUNTS_TITLE.getTranslatedName()));
    }

    @Override
    protected void init() {
        if (scene == null) {
            scene = new UiScene(EpsilonUiTheme.INSTANCE);
            textRenderer = TextRenderer.create();
            tokenInput = new ClientSettingTextField(512);
        }
        // 每次打开时重置选中
        selectedIndex = -1;
        accountRowBounds.clear();
        for (int i = 0; i < buttonHovers.length; i++) {
            buttonHovers[i] = 0f;
        }
    }

    @Override
    public void removed() {
        super.removed();
        if (tokenInput != null && tokenInput.isFocused()) {
            tokenInput.blur();
        }
        if (renderTarget != null) {
            renderTarget.close();
            renderTarget = null;
        }
        if (textRenderer != null) {
            textRenderer.close();
            textRenderer = null;
        }
        if (scene != null) {
            scene.close();
            scene = null;
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(MainMenuScreen.INSTANCE);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ── 文本工具 ──────────────────────────────────────────────────────────────

    /**
     * 文本超出可用宽度时做尾部缩限，超出部分用 "…" 替代。
     */
    private String ellipsizeText(String text, float maxWidth, float textScale) {
        if (text == null || text.isEmpty()) return "";
        float fullWidth = textRenderer.getWidth(text, textScale);
        if (fullWidth <= maxWidth) return text;

        float ellipsisW = textRenderer.getWidth("…", textScale);
        float available = maxWidth - ellipsisW;
        if (available <= 0) return "…";

        // 二分查找最大可容纳字符数
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (textRenderer.getWidth(text.substring(0, mid), textScale) <= available) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + "…";
    }

    // ── 业务方法 ──────────────────────────────────────────────────────────────

    private String getClipboard() {
        try {
            var cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            var data = cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return data != null ? (String) data : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void addAccount() {
        String token = tokenInput.getText().trim();
        if (token.isEmpty()) token = getClipboard().trim();
        addAccountDirect(token);
    }

    private void addAccountDirect(String token) {
        if (token.isEmpty()) {
            accountStatus.put(-1, EpsilonTranslations.Gui.ACCOUNTS_STATUS_TOKEN_EMPTY.getTranslatedName());
            return;
        }
        final String t = token;
        Constants.LOGGER.info("[Account] Starting add, token length={}", t.length());

        CompletableFuture.runAsync(() -> {
            try {
                Account a = Managers.ACCOUNT.addSessionAccount(t);
                Constants.LOGGER.info("[Account] Auth OK: {}", a.name);
                if (minecraft.gui.screen() != AccountManagerScreen.this) return;
                minecraft.execute(() -> {
                    tokenInput.clear();
                    clearAndRebuild();
                    // 找到新增账号的索引，显示 EpsilonTranslations.Gui.ACCOUNTS_STATUS_ADDED.getTranslatedName() 状态
                    var accounts = Managers.ACCOUNT.getAccounts();
                    for (int i = 0; i < accounts.size(); i++) {
                        if (accounts.get(i).uuid.equalsIgnoreCase(a.uuid)) {
                            accountStatus.put(i, EpsilonTranslations.Gui.ACCOUNTS_STATUS_ADDED.getTranslatedName());
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                Constants.LOGGER.error("[Account] Auth FAILED", e);
                if (minecraft.gui.screen() != AccountManagerScreen.this) return;
                minecraft.execute(() -> accountStatus.put(-1, EpsilonTranslations.Gui.ACCOUNTS_STATUS_FAILED.getTranslatedName()));
            }
        });
    }

    private void loginAccount() {
        var list = Managers.ACCOUNT.getAccounts();
        if (selectedIndex < 0 || selectedIndex >= list.size()) {
            accountStatus.put(-1, EpsilonTranslations.Gui.ACCOUNTS_STATUS_SELECT.getTranslatedName());
            return;
        }
        Account a = list.get(selectedIndex);
        Constants.LOGGER.info("[Account] Logging in: {}", a.name);
        accountStatus.put(selectedIndex, EpsilonTranslations.Gui.ACCOUNTS_STATUS_LOGGING_IN.getTranslatedName());

        CompletableFuture.runAsync(() -> {
            try {
                Managers.ACCOUNT.login(a);
                Constants.LOGGER.info("[Account] Login OK: {}", a.name);
                if (minecraft.gui.screen() != AccountManagerScreen.this) return;
                minecraft.execute(() -> accountStatus.put(selectedIndex, EpsilonTranslations.Gui.ACCOUNTS_STATUS_LOGGED_IN.getTranslatedName()));
            } catch (Exception e) {
                Constants.LOGGER.error("[Account] Login FAILED", e);
                if (minecraft.gui.screen() != AccountManagerScreen.this) return;
                minecraft.execute(() -> accountStatus.put(selectedIndex, EpsilonTranslations.Gui.ACCOUNTS_STATUS_FAILED.getTranslatedName()));
            }
        });
    }

    private void deleteAccount() {
        var list = Managers.ACCOUNT.getAccounts();
        if (selectedIndex < 0 || selectedIndex >= list.size()) return;
        Managers.ACCOUNT.remove(list.get(selectedIndex));
        if (selectedIndex >= list.size()) selectedIndex = Math.max(0, list.size() - 1);
        accountStatus.clear();
        clearAndRebuild();
    }

    private void clearAndRebuild() {
        accountRowBounds.clear();
    }

    // ── 渲染管线 ──────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        var window = minecraft.getWindow();
        if (renderTarget == null) {
            renderTarget = LuminRenderSystem.LuminRenderTarget.create(
                    "account-manager", window.getWidth(), window.getHeight());
        }
        renderTarget.clear();
        renderTarget.resize(window.getWidth(), window.getHeight());
        LuminRenderSystem.setActiveTarget(renderTarget);

        scene.beginFrame();
        int emx = LuminRenderSystem.toEpsilonMouseX(mouseX);
        int emy = LuminRenderSystem.toEpsilonMouseY(mouseY);
        drawScreen(emx, emy);
        scene.endFrame();

        LuminRenderSystem.setActiveTarget(null);
        graphics.blit(renderTarget.getIdentifier(), 0, 0,
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), 0, 1, 1, 0);
    }

    private void drawScreen(int mouseX, int mouseY) {
        int w = LuminRenderSystem.getScaledWidthInt();
        int h = LuminRenderSystem.getScaledHeightInt();
        float scale = Mth.clamp((w * 2f + h) / 900f + 0.08f, 0.72f, 1.24f);

        float contentW = Math.min(400 * scale, w - 40 * scale);
        float contentLeft = (w - contentW) / 2f;

        // 标题
        float titleScale = 1.5f * scale;
        float titleY = 30 * scale;
        float titleH = textRenderer.getHeight(titleScale);
        // 分隔线
        float sepY = titleY + titleH + 8 * scale;
        // 全局状态提示
        final String globalStatus;
        String s1 = accountStatus.get(-1);
        String s2 = accountStatus.get(-2);
        globalStatus = s1 != null ? s1 : s2;
        // 账号列表
        float listTop = sepY + 14 * scale + (globalStatus != null ? 16 * scale : 0);
        float rowH = 26 * scale;
        float rowGap = 2 * scale;
        float maxListH = h - listTop - 175 * scale;
        int maxVisible = Math.max(0, (int) ((maxListH + rowGap) / (rowH + rowGap)));
        // 输入框
        float inputH = 26 * scale;
        float pasteBtnW = 50 * scale;
        float inputW = contentW - pasteBtnW - 6 * scale;
        float inputY = h - 145 * scale;
        // 按钮
        float btnGap = 6 * scale;
        float actionBtnW = (contentW - 2 * btnGap) / 3f;
        float btnH = 22 * scale;
        float actionBtnY = inputY + inputH + 8 * scale;
        float offlineBtnY = actionBtnY + btnH + 6 * scale;
        float offlineBtnW = (contentW - btnGap) / 2f;
        float clipBtnY = offlineBtnY + btnH + 6 * scale;
        float doneBtnY = clipBtnY + btnH + 6 * scale;
        float doneBtnW = 80 * scale;

        // 每帧重建命中测试列表
        accountRowBounds.clear();

        var accounts = Managers.ACCOUNT.getAccounts();

        UiTree tree = UiTree.build(scope -> {
            // ── 背景 ──
            scope.rect(0, 0, w, h, MD3Theme.SURFACE);

            // ── 标题 ──
            scope.text(EpsilonTranslations.Gui.ACCOUNTS_TITLE.getTranslatedName(),
                    contentLeft, titleY, titleScale,
                    MD3Theme.TEXT_PRIMARY, StaticFontLoader.DEFAULT);

            // ── 分隔线 ──
            scope.rect(contentLeft, sepY, contentW, Math.max(1f, scale),
                    MD3Theme.OUTLINE_SOFT);

            // ── 全局状态提示（-1 / -2 key）──
            if (globalStatus != null) {
                float statusScale = 0.7f * scale;
                scope.text(globalStatus, contentLeft, sepY + 6 * scale,
                        statusScale, MD3Theme.TEXT_MUTED, StaticFontLoader.DEFAULT);
            }

            // ── 账号列表 ──
            for (int i = 0; i < Math.min(accounts.size(), maxVisible); i++) {
                Account a = accounts.get(i);
                float rowY = listTop + i * (rowH + rowGap);
                UiRect rowBounds = new UiRect(contentLeft, rowY, contentW, rowH);
                accountRowBounds.add(rowBounds);

                boolean hovered = rowBounds.contains(mouseX, mouseY);
                boolean selected = i == selectedIndex;

                // hover 动画（复用全局 buttonHovers 数组的最后几个元素不太好，
                // 这里对列表项使用简单的即时 hover 检测，无动画过渡以保持简洁）
                Color rowBg;
                Color rowText;
                if (selected) {
                    rowBg = MD3Theme.PRIMARY_CONTAINER;
                    rowText = MD3Theme.ON_PRIMARY_CONTAINER;
                } else if (hovered) {
                    rowBg = MD3Theme.SURFACE_CONTAINER_HIGH;
                    rowText = MD3Theme.TEXT_PRIMARY;
                } else {
                    rowBg = new Color(0, 0, 0, 0); // 透明
                    rowText = MD3Theme.TEXT_SECONDARY;
                }

                if (rowBg.getAlpha() > 0) {
                    scope.roundRect(rowBounds.x(), rowBounds.y(),
                            rowBounds.width(), rowBounds.height(),
                            6 * scale, rowBg);
                }

                String label = a.name + "  [" + a.type + "]";
                float textScale = 0.8f * scale;
                float textH = textRenderer.getHeight(textScale);
                float textY = rowBounds.y() + (rowBounds.height() - textH) / 2f;
                scope.text(label, rowBounds.x() + 10 * scale, textY, textScale, rowText,
                        StaticFontLoader.DEFAULT);

                String status = accountStatus.get(i);
                if (status != null) {
                    float statusScale = 0.65f * scale;
                    Color statusColor = MD3Theme.TERTIARY;
                    float statusW = textRenderer.getWidth(status, statusScale);
                    scope.text(status, rowBounds.right() - statusW - 10 * scale,
                            textY, statusScale, statusColor, StaticFontLoader.DEFAULT);
                }
            }

            // ── Token 输入框 ──
            inputBounds = new UiRect(contentLeft, inputY, inputW, inputH);
            String tokenText = tokenInput.getText();
            if (tokenInput.isFocused()) {
                // 聚焦时：scissor 裁剪，光标/文字溢出部分直接截断
                scope.scissor(inputBounds.x(), inputBounds.y(),
                        inputBounds.width(), inputBounds.height(), sub -> {
                    tokenInput.buildUi(sub, inputBounds, mouseX, mouseY,
                            textRenderer, EpsilonTranslations.Gui.ACCOUNTS_TOKEN_PLACEHOLDER.getTranslatedName(), 0.78f * scale, null);
                });
            } else if (!tokenText.isEmpty()) {
                // 失焦时：尾部缩限 + "…"
                String truncated = ellipsizeText(tokenText, inputW - 20f, 0.78f * scale);
                if (!truncated.equals(tokenText)) {
                    tokenInput.setText(truncated);
                    tokenInput.buildUi(scope, inputBounds, mouseX, mouseY,
                            textRenderer, EpsilonTranslations.Gui.ACCOUNTS_TOKEN_PLACEHOLDER.getTranslatedName(), 0.78f * scale, null);
                    tokenInput.setText(tokenText);
                } else {
                    tokenInput.buildUi(scope, inputBounds, mouseX, mouseY,
                            textRenderer, EpsilonTranslations.Gui.ACCOUNTS_TOKEN_PLACEHOLDER.getTranslatedName(), 0.78f * scale, null);
                }
            } else {
                tokenInput.buildUi(scope, inputBounds, mouseX, mouseY,
                        textRenderer, EpsilonTranslations.Gui.ACCOUNTS_TOKEN_PLACEHOLDER.getTranslatedName(), 0.78f * scale, null);
            }

            // ── Paste 按钮（输入框右侧）──
            drawButton(scope, BTN_PASTE, EpsilonTranslations.Gui.ACCOUNTS_PASTE.getTranslatedName(),
                    contentLeft + inputW + 6 * scale, inputY, pasteBtnW, inputH,
                    6 * scale, 0.7f * scale, mouseX, mouseY);

            // ── 操作按钮行：Add / Login / Delete ──
            for (int i = 0; i < 3; i++) {
                float bx = contentLeft + i * (actionBtnW + btnGap);
                String label = switch (i) {
                    case 0 -> EpsilonTranslations.Gui.ACCOUNTS_ADD.getTranslatedName();
                    case 1 -> EpsilonTranslations.Gui.ACCOUNTS_LOGIN.getTranslatedName();
                    default -> EpsilonTranslations.Gui.ACCOUNTS_DELETE.getTranslatedName();
                };
                int btnId = switch (i) {
                    case 0 -> BTN_ADD;
                    case 1 -> BTN_LOGIN;
                    default -> BTN_DELETE;
                };
                // Delete 按钮 hover 时变红
                boolean isDelete = i == 2;
                drawButton(scope, btnId, label, bx, actionBtnY, actionBtnW, btnH,
                        6 * scale, 0.72f * scale, mouseX, mouseY, isDelete);
            }

            // ── 离线 / 微软登录 ──
            drawButton(scope, BTN_OFFLINE, EpsilonTranslations.Gui.ACCOUNTS_OFFLINE.getTranslatedName(),
                    contentLeft, offlineBtnY, offlineBtnW, btnH,
                    6 * scale, 0.7f * scale, mouseX, mouseY);
            drawButton(scope, BTN_MS_LOGIN, EpsilonTranslations.Gui.ACCOUNTS_MICROSOFT.getTranslatedName(),
                    contentLeft + offlineBtnW + btnGap, offlineBtnY, offlineBtnW, btnH,
                    6 * scale, 0.7f * scale, mouseX, mouseY);

            // ── Add from Clipboard 按钮（全宽）──
            drawButton(scope, BTN_ADD_CLIP, EpsilonTranslations.Gui.ACCOUNTS_ADD_CLIPBOARD.getTranslatedName(),
                    contentLeft, clipBtnY, contentW, btnH,
                    6 * scale, 0.7f * scale, mouseX, mouseY);

            // ── Done 按钮（居中）──
            float doneX = (w - doneBtnW) / 2f;
            drawButton(scope, BTN_DONE, EpsilonTranslations.Gui.ACCOUNTS_DONE.getTranslatedName(), doneX, doneBtnY, doneBtnW, btnH,
                    6 * scale, 0.72f * scale, mouseX, mouseY);
        });

        scene.submit(UiLayer.CONTENT, tree);
    }

    // ── 按钮绘制辅助 ───────────────────────────────────────────────────────────

    private void drawButton(UiTree.Scope scope, int id, String label,
                            float x, float y, float w, float h,
                            float radius, float textScale,
                            int mouseX, int mouseY) {
        drawButton(scope, id, label, x, y, w, h, radius, textScale,
                mouseX, mouseY, false);
    }

    private void drawButton(UiTree.Scope scope, int id, String label,
                            float x, float y, float w, float h,
                            float radius, float textScale,
                            int mouseX, int mouseY,
                            boolean danger) {
        UiRect bounds = new UiRect(x, y, w, h);
        buttonBounds[id] = bounds;

        boolean hovered = bounds.contains(mouseX, mouseY);
        float target = hovered ? 1f : 0f;
        buttonHovers[id] = Mth.lerp(0.24f, buttonHovers[id], target);
        float hp = buttonHovers[id];

        Color bgDefault = MD3Theme.SURFACE_CONTAINER;
        Color bgHover = danger ? MD3Theme.withAlpha(MD3Theme.ERROR, 48)
                               : MD3Theme.SURFACE_CONTAINER_HIGHEST;
        Color bg = MD3Theme.lerp(bgDefault, bgHover, hp);
        Color fg = MD3Theme.lerp(MD3Theme.TEXT_SECONDARY,
                danger ? MD3Theme.ERROR : MD3Theme.PRIMARY, hp);

        scope.roundRect(x, y, w, h, radius, bg);
        scope.outline(x, y, w, h, radius, 1f, MD3Theme.OUTLINE_SOFT);

        float textH = textRenderer.getHeight(textScale);
        float textW = textRenderer.getWidth(label, textScale);
        float textX = x + (w - textW) / 2f;
        float textY = y + (h - textH) / 2f;
        scope.text(label, textX, textY, textScale, fg, StaticFontLoader.DEFAULT);
    }

    // ── 事件处理 ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MouseButtonEvent ee = LuminRenderSystem.toEpsilonMouseEvent(event);
        double mx = ee.x();
        double my = ee.y();

        // 1. 右键菜单（由 ClientSettingTextField 内部处理）
        if (ee.button() == 1 && tokenInput != null) {
            if (tokenInput.isContextMenuOpen()) {
                if (tokenInput.contextMenuClicked(mx, my, ee.button())) return true;
            }
            if (inputBounds.contains(mx, my)) {
                tokenInput.rightClicked(inputBounds, mx, my, ee.button());
                return true;
            }
        }

        // 2. 左键
        if (ee.button() == 0) {
            // 关闭右键菜单（如果开着）
            if (tokenInput != null && tokenInput.isContextMenuOpen()) {
                tokenInput.closeContextMenu();
                return true;
            }

            // 检查输入框
            if (tokenInput != null) {
                if (inputBounds.contains(mx, my)) {
                    tokenInput.mousePressed(inputBounds, mx, my, ee.button());
                    return true;
                } else if (tokenInput.isFocused()) {
                    tokenInput.blur();
                }
            }

            // 检查账号列表
            for (int i = 0; i < accountRowBounds.size(); i++) {
                if (accountRowBounds.get(i).contains(mx, my)) {
                    if (selectedIndex != i) accountStatus.clear();
                    selectedIndex = i;
                    return true;
                }
            }

            // 检查按钮
            for (int i = 0; i < buttonBounds.length; i++) {
                UiRect b = buttonBounds[i];
                if (b != null && b.contains(mx, my)) {
                    executeButton(i);
                    return true;
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double mouseX, double mouseY) {
        MouseButtonEvent ee = LuminRenderSystem.toEpsilonMouseEvent(event);
        if (tokenInput != null && tokenInput.isFocused()) {
            tokenInput.mouseDragged(inputBounds, ee.x(), ee.y());
            return true;
        }
        return super.mouseDragged(event, mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (tokenInput != null) {
            tokenInput.mouseReleased(inputBounds, 0, 0, 0);
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 优先路由到输入框
        if (tokenInput != null && tokenInput.isFocused()) {
            if (tokenInput.keyPressed(event)) return true;
        }

        // 全局快捷键
        if (event.key() == 256) { // GLFW_KEY_ESCAPE
            onClose();
            return true;
        }
        if (event.key() == 257 || event.key() == 335) { // Enter / KP Enter
            addAccount();
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (tokenInput != null && tokenInput.isFocused()) {
            return tokenInput.charTyped(event);
        }
        return super.charTyped(event);
    }

    private void executeButton(int id) {
        switch (id) {
            case BTN_PASTE -> tokenInput.setText(getClipboard());
            case BTN_ADD -> addAccount();
            case BTN_LOGIN -> loginAccount();
            case BTN_DELETE -> deleteAccount();
            case BTN_OFFLINE -> offlineLogin();
            case BTN_MS_LOGIN -> startMicrosoftLoginFlow();
            case BTN_ADD_CLIP -> {
                String clip = getClipboard();
                if (!clip.isEmpty()) addAccountDirect(clip);
            }
            case BTN_DONE -> onClose();
        }
    }

    // ── 离线登录 ──────────────────────────────────────────────────────────────

    private void offlineLogin() {
        String name = tokenInput.getText().trim();
        if (name.isEmpty()) {
            accountStatus.put(-1, EpsilonTranslations.Gui.ACCOUNTS_STATUS_ENTER_NAME.getTranslatedName());
            return;
        }
        var account = Managers.ACCOUNT.addOfflineAccount(name);
        if (account == null) {
            accountStatus.put(-1, EpsilonTranslations.Gui.ACCOUNTS_STATUS_FAILED.getTranslatedName());
            return;
        }
        Managers.ACCOUNT.loginOffline(account);
        tokenInput.clear();
        clearAndRebuild();
        // 找到离线账号的索引，显示状态
        var accounts = Managers.ACCOUNT.getAccounts();
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).uuid.equalsIgnoreCase(account.uuid)) {
                accountStatus.put(i, EpsilonTranslations.Gui.ACCOUNTS_STATUS_LOGGED_IN.getTranslatedName());
                break;
            }
        }
    }

    // ── 微软浏览器 OAuth 登录 ────────────────────────────────────────────────

    private void startMicrosoftLoginFlow() {
        accountStatus.put(-2, EpsilonTranslations.Gui.ACCOUNTS_STATUS_OPENING_BROWSER.getTranslatedName());
        CompletableFuture.runAsync(() -> {
            try {
                Account account = Managers.ACCOUNT.microsoftLogin();
                minecraft.execute(() -> {
                    if (minecraft.gui.screen() != AccountManagerScreen.this) return;
                    accountStatus.remove(-2);
                    clearAndRebuild();
                    var accounts = Managers.ACCOUNT.getAccounts();
                    for (int i = 0; i < accounts.size(); i++) {
                        if (accounts.get(i).uuid.equalsIgnoreCase(account.uuid)) {
                            accountStatus.put(i, EpsilonTranslations.Gui.ACCOUNTS_STATUS_LOGGED_IN.getTranslatedName());
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                Constants.LOGGER.error("[Account] MS Login FAILED", e);
                minecraft.execute(() -> {
                    if (minecraft.gui.screen() != AccountManagerScreen.this) return;
                    accountStatus.put(-2, EpsilonTranslations.Gui.ACCOUNTS_STATUS_FAILED.getTranslatedName());
                });
            }
        });
    }
}
