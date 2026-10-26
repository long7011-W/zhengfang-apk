// Execute in the userScript compartment, separately from the unchanged upstream.
// Native DOM members keep Xray wrappers so callbacks stay callable. Page globals
// use the waived window, as expected by scripts reading e.g. ServerHost or jQuery.
(() => {
  const windows = new WeakMap();
  const wrap = target => {
    if (windows.has(target)) return windows.get(target);
    const methods = new WeakMap();
    const proxy = new Proxy(target, {
      get(target, key) {
        if (['window', 'self', 'top', 'parent', 'frames'].includes(key)) return wrap(Reflect.get(target, key, target));
        if (!(key in target)) return target.wrappedJSObject[key];
        const value = Reflect.get(target, key, target);
        if (typeof value !== 'function') return value;
        if (!methods.has(value)) methods.set(value, value.bind(target));
        return methods.get(value);
      },
      set(target, key, value) {
        const destination = key in target ? target : target.wrappedJSObject;
        return Reflect.set(destination, key, value, destination);
      }
    });
    windows.set(target, proxy);
    return proxy;
  };
  // Keep iframe DOM nodes native, but give their windows the same page-global
  // access as unsafeWindow. Xrays otherwise hide helpers such as noSubmit even
  // though the original script can read the iframe's native document.
  const framePrototype = typeof HTMLIFrameElement === 'function' ? HTMLIFrameElement.prototype : null;
  const frameWindow = framePrototype && Object.getOwnPropertyDescriptor(framePrototype, 'contentWindow');
  if (frameWindow?.get) Object.defineProperty(framePrototype, 'contentWindow', {
    ...frameWindow,
    get() {
      const child = frameWindow.get.call(this);
      return child == null ? child : wrap(child);
    }
  });
  Object.defineProperty(globalThis, 'unsafeWindow', {value: wrap(window), configurable: true});
})();
