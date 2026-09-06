/**
 * Startup argument handling for the Electron main process.
 *
 * Pure and Electron-free on purpose: `main.ts` cannot be loaded outside an Electron runtime, so any
 * logic left in it is untestable by construction. This is the part with decisions in it.
 */

/**
 * Picks the file to open from a process argv.
 *
 * @param argv the raw process arguments, executable first
 * @param isDevelopment whether argv carries a script/directory argument before user arguments, as
 *   `electron . file.java` does — mistaking that for the file opens the app directory instead
 */
export function resolveInitialFile(argv: readonly string[], isDevelopment: boolean): string | null {
  const afterExecutable = argv.slice(isDevelopment ? 2 : 1);
  const firstPath = afterExecutable.find((argument) => !argument.startsWith('-'));
  return firstPath ?? null;
}
