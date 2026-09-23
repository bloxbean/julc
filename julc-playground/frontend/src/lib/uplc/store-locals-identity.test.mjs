import assert from 'node:assert/strict';
import test from 'node:test';
import {localsIdentityKey, localsResponseMatches} from './locals-identity.js';

test('delayed children response cannot cross a replacement compilation with the same generation', async () => {
  const oldIdentity = localsIdentityKey({
    sessionId: 'session-a', bindingKey: 'source-a', artifactIdentity: 'artifact-a', revision: 4, generation: 2,
  });
  let currentIdentity = oldIdentity;
  let release;
  const delayed = new Promise((resolve) => { release = resolve; });

  const pending = (async () => {
    const response = await delayed;
    return localsResponseMatches(oldIdentity, currentIdentity, 2, response.stopGeneration);
  })();
  currentIdentity = localsIdentityKey({
    sessionId: 'session-b', bindingKey: 'source-b', artifactIdentity: 'artifact-b', revision: 5, generation: 2,
  });
  release({ok: true, stopGeneration: 2, children: []});

  assert.equal(await pending, false);
});

test('response generation must match even when session identity is unchanged', () => {
  const identity = localsIdentityKey({
    sessionId: 'session-a', bindingKey: 'source-a', artifactIdentity: 'artifact-a', revision: 4, generation: 3,
  });
  assert.equal(localsResponseMatches(identity, identity, 3, 2), false);
  assert.equal(localsResponseMatches(identity, identity, 3, 3), true);
});
