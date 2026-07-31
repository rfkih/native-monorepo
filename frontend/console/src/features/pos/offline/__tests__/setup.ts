/** Polyfills `indexedDB`/`IDBKeyRange` globally so db.ts's `idb` calls work under vitest's `node`
 * environment, exactly like a real browser's IndexedDB (fake-indexeddb is a spec-faithful
 * in-memory implementation, not a mock). */
import 'fake-indexeddb/auto'
