type FileIdentity = Pick<File, 'name' | 'size' | 'type' | 'lastModified'>;

function isSameFile(left: FileIdentity, right: FileIdentity): boolean {
  return left.name === right.name
    && left.size === right.size
    && left.type === right.type
    && left.lastModified === right.lastModified;
}

/** Keeps existing selections, appends new files, and ignores exact re-selections. */
export function mergeSelectedFiles<T extends FileIdentity>(current: T[], selected: Iterable<T>): T[] {
  const merged = [...current];
  for (const file of selected) {
    if (!merged.some((existing) => isSameFile(existing, file))) {
      merged.push(file);
    }
  }
  return merged;
}

/** Removes only the exact File objects owned by a completed send snapshot. */
export function removeSelectedFiles<T>(current: T[], completed: Iterable<T>): T[] {
  const completedFiles = new Set(completed);
  if (completedFiles.size === 0) return current;

  const remaining = current.filter((file) => !completedFiles.has(file));
  return remaining.length === current.length ? current : remaining;
}

export function splitFilesBySize<T extends Pick<File, 'size'>>(
  selected: Iterable<T>,
  maxBytes: number,
): { accepted: T[]; rejected: T[] } {
  const accepted: T[] = [];
  const rejected: T[] = [];
  for (const file of selected) {
    (file.size <= maxBytes ? accepted : rejected).push(file);
  }
  return { accepted, rejected };
}
