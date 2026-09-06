/**
 * Input-to-frame latency measurement.
 *
 * The browser exposes no direct "a paint has occurred" signal. `requestPostAnimationFrame` was
 * proposed and archived without shipping, so the accepted technique is to schedule a
 * `requestAnimationFrame` callback — which runs just before the render steps — and from inside it
 * post a `MessageChannel` message, whose task runs after the frame has been produced.
 *
 * `setTimeout(0)` is not a substitute: Chromium clamps nested timers to 4 ms and does not order
 * them after the render steps.
 *
 * This measures to the renderer's frame commit, not to photons. End-to-end display latency is not
 * observable from page JavaScript at all — see docs/adr/0001-editor-responsiveness-budgets.md.
 */

export type Clock = () => number;
export type AfterFrame = (callback: () => void) => void;

/** Schedules a callback to run after the next frame has been produced. */
export const afterNextFrame: AfterFrame = (callback) => {
  requestAnimationFrame(() => {
    const channel = new MessageChannel();
    channel.port1.onmessage = () => callback();
    channel.port2.postMessage(undefined);
  });
};

/**
 * Measures from an input event's timestamp to the next committed frame.
 *
 * @param inputTimestamp the event's own `timeStamp`, not the time the handler started — the input
 *   delay before the handler runs is part of what we are measuring.
 */
export function measureToNextFrame(
  inputTimestamp: number,
  now: Clock = () => performance.now(),
  afterFrame: AfterFrame = afterNextFrame,
): Promise<number> {
  return new Promise((resolve) => {
    afterFrame(() => {
      resolve(Math.max(0, now() - inputTimestamp));
    });
  });
}
