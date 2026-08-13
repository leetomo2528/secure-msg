/**
 * Serializes local operations which cross an authentication boundary.
 *
 * Network calls deliberately stay outside this queue.  Only short local
 * session installation, cleanup, and session-owned IndexedDB effects belong
 * here, so logout can drain an already-started transaction before clearing it
 * and a new login cannot install data until that clear has completed.
 */
class SessionCoordinator {
  private tail: Promise<void> = Promise.resolve();

  async exclusive<T>(operation: () => Promise<T>): Promise<T> {
    const previous = this.tail;
    let release!: () => void;
    this.tail = new Promise<void>((resolve) => { release = resolve; });
    await previous.catch(() => undefined);
    try {
      return await operation();
    } finally {
      release();
    }
  }
}

export const sessionCoordinator = new SessionCoordinator();
