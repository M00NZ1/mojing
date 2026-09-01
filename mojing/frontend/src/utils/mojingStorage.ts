/** 墨境 localStorage 的统一读写入口。 */
export function readMoJingStorage(key: string): string | null {
  return localStorage.getItem(key);
}

export function writeMoJingStorage(key: string, value: string): void {
  localStorage.setItem(key, value);
}

export function removeMoJingStorage(key: string): void {
  localStorage.removeItem(key);
}
