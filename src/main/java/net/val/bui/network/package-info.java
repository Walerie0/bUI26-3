/**
 * Common-side namespaced server actions and their validated client request payload.
 * Register each ID once with {@link net.val.bui.network.BUIServerActions#registerAction(String, java.util.function.Consumer)}
 * and bind that same ID through {@link net.val.bui.component.BUINodeAction#onPressServer(String)}.
 * Registered callbacks must validate the requesting player and the current game state.
 */
package net.val.bui.network;
