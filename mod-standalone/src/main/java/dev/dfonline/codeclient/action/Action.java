package dev.dfonline.codeclient.action;

import dev.dfonline.codeclient.Callback;
import dev.dfonline.codeclient.Feature;

public abstract class Action extends Feature {
    private final Callback callback;

    public Action(Callback callback) {
        this.callback = callback;
    }

    public abstract void init();

    protected void callback() {
        this.callback.run();
    }

    /**
     * Whether this action wants NoClip's wall handling active while it runs. Actions that fly the
     * player around the codespace (placing/scanning) override this to true so the same
     * server-position sanitization that makes manual dev-mode flight smooth (never reporting a
     * position inside a code block) applies to them too — otherwise the player is rubber-banded
     * for pushing sideways into blocks. Default false (most actions don't move the player).
     */
    public boolean wantsNoClip() {
        return false;
    }
}
