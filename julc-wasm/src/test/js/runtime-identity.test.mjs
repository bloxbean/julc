// Which runtime an engine runs on (ADR-077 D2): JsApi.install's own JavaScript, run in a fresh global scope, as an
// image's main thread runs it. A runtime the worker recorded as preloaded must be the image's build; without a
// recorded preload, a runtime some other engine left on a shared global is used only when it is the same build.
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {CLIENT_VERSION, jsApiInstall, runtimeStamped, runtimeText} from './support.mjs';

const A = '0.1.0-a', B = '0.1.0-b';
// Objects made in another global scope have its prototypes.
const plain = value => JSON.parse(JSON.stringify(value));
/** A global scope like a worker's or a page's main thread; strings: false is a Content Security Policy without 'unsafe-eval'. */
function scope({strings = true} = {}) {
  const errors = [];
  const context = vm.createContext({setTimeout, console, onJulcStartupError: message => errors.push(message)},
    {codeGeneration: {strings, wasm: true}});
  vm.runInContext('globalThis.self = globalThis;', context);
  return {context, errors, run: code => vm.runInContext(code, context)};
}
/** Starts an image of the given JuLC build under the given global name, as its main does. */
function install(target, {julcVersion = CLIENT_VERSION, source = runtimeText, name = 'julc', groups = ['vm']} = {}) {
  const fn = vm.compileFunction(jsApiInstall.body, jsApiInstall.args, {parsingContext: target.context});
  fn(argv => JSON.stringify({status: 200, body: {method: argv[0], julcVersion}, integers: []}),
    JSON.stringify({'vm.echo': {}}), name, 'vm', julcVersion, JSON.stringify(groups), julcVersion, source);
  return target.context[name];
}
const preload = (target, runtime, state = 'loaded') => {
  target.run('globalThis.JULC_RUNTIME_PRELOAD = "pending";');
  target.run(runtime);
  if (state === 'loaded') target.run('globalThis.JULC_RUNTIME_PRELOAD = "loaded";');
};

test('a recorded preload of the image\'s own build is used, and nothing is evaluated', () => {
  const target = scope({strings: false});
  preload(target, runtimeText);
  const api = install(target);
  assert.equal(api.__runtime, 'preloaded');
  assert.equal(api.features().julcVersion, CLIENT_VERSION);
  assert.deepEqual(plain(api.vm.echo({})), {method: 'vm.echo', julcVersion: CLIENT_VERSION});
});

test('a recorded preload of another build, unstamped, malformed or failed stops the engine, naming both versions', () => {
  const cases = [
    [runtimeStamped(A), 'loaded', `the runtime file julc-runtime.js loaded before it is JuLC ${A}`],
    [runtimeStamped(null), 'loaded', 'is not a stamped JuLC runtime'],
    [`globalThis.JulcRuntime = {julcVersion: ${JSON.stringify(CLIENT_VERSION)}};`, 'loaded', 'is not a stamped JuLC runtime'],
    ['globalThis.JulcRuntime = 42;', 'loaded', 'is not a stamped JuLC runtime'],
    ['', 'pending', 'the runtime file julc-runtime.js loaded before it is missing'],
  ];
  for (const [runtime, state, cause] of cases) {
    const target = scope();
    preload(target, runtime, state);
    assert.throws(() => install(target), error => error.message.includes(cause)
      && error.message.startsWith(`JuLC engine ${CLIENT_VERSION} cannot start: `), cause);
    // Reported to the worker before main fails, so the client hears the cause at once.
    assert.equal(target.errors.length, 1);
    assert.match(target.errors[0], new RegExp(`serve the julc-runtime.js of JuLC ${CLIENT_VERSION.replace(/[.]/g, '\\.')}`));
    assert.equal(target.context.julc, undefined, 'no engine is installed');
  }
});

test('without a preload, an image evaluates its own runtime; a page that forbids that gets an explicit error', () => {
  const target = scope();
  const api = install(target);
  assert.equal(api.__runtime, 'evaluated');
  assert.equal(target.context.JulcRuntime.julcVersion, CLIENT_VERSION);
  const strict = scope({strings: false});
  assert.throws(() => install(strict), /cannot start: the runtime file julc-runtime\.js was not loaded, and evaluating the engine's own copy failed \(a Content Security Policy without 'unsafe-eval' forbids it\): EvalError/);
  assert.match(strict.errors[0], /was not loaded, and evaluating the engine's own copy failed/);
});

test('engines of different builds share one main-thread global without a preload, and both work', () => {
  const target = scope();
  const first = install(target, {julcVersion: A, source: runtimeStamped(A), name: 'first'});
  assert.equal(first.__runtime, 'evaluated');
  const runtimeA = target.context.JulcRuntime;
  const second = install(target, {julcVersion: B, source: runtimeStamped(B), name: 'second'});
  assert.equal(second.__runtime, 'evaluated');
  // The second engine runs on its own build's runtime and leaves the global the first one uses.
  assert.equal(target.context.JulcRuntime, runtimeA);
  assert.equal(first.features().julcVersion, A);
  assert.equal(second.features().julcVersion, B);
  assert.deepEqual(plain(first.vm.echo({})), {method: 'vm.echo', julcVersion: A});
  assert.deepEqual(plain(second.vm.echo({})), {method: 'vm.echo', julcVersion: B});
  // A third engine of the first build may use the runtime already there.
  const third = install(target, {julcVersion: A, source: runtimeStamped(A), name: 'third'});
  assert.equal(third.__runtime, 'shared');
});

test('an engine of this build loaded after a pre-ADR-077 engine (unstamped runtime) on one global works', () => {
  const target = scope();
  target.run(runtimeStamped(null));
  const old = target.context.JulcRuntime;
  assert.equal(old.julcVersion, undefined);
  const api = install(target);
  assert.equal(api.__runtime, 'evaluated');
  assert.equal(target.context.JulcRuntime, old);
  assert.deepEqual(plain(api.vm.echo({})), {method: 'vm.echo', julcVersion: CLIENT_VERSION});
});
