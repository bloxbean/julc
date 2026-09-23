/** Build an opaque comparison key for one exact source-debug locals stop. */
export function localsIdentityKey({sessionId, bindingKey, artifactIdentity, revision, generation}) {
  if (!sessionId || !bindingKey || !artifactIdentity || generation == null) return null;
  return JSON.stringify([
    String(sessionId), String(bindingKey), String(artifactIdentity), String(revision), String(generation),
  ]);
}

/** Accept a response only for the exact identity captured before the asynchronous request. */
export function localsResponseMatches(capturedKey, currentKey, capturedGeneration, responseGeneration) {
  return capturedKey != null && capturedKey === currentKey
    && responseGeneration != null && String(capturedGeneration) === String(responseGeneration);
}
