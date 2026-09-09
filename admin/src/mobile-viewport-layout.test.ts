import indexHtml from "../index.html?raw";
import styles from "./styles.css?raw";

function declarationBlock(selector: string, css = styles): string {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = css.match(
    new RegExp(`(?:^|\\})\\s*${escapedSelector}\\s*\\{([^}]*)\\}`, "m"),
  );
  if (!match) throw new Error(`Missing CSS block for ${selector}`);
  return match[1];
}

function atRuleBlock(header: string): string {
  const headerIndex = styles.indexOf(header);
  if (headerIndex < 0) throw new Error(`Missing CSS at-rule ${header}`);
  const openingBrace = styles.indexOf("{", headerIndex);
  let depth = 0;
  for (let index = openingBrace; index < styles.length; index += 1) {
    if (styles[index] === "{") depth += 1;
    if (styles[index] !== "}") continue;
    depth -= 1;
    if (depth === 0) return styles.slice(openingBrace + 1, index);
  }
  throw new Error(`Unclosed CSS at-rule ${header}`);
}

describe("mobile viewport layout", () => {
  it("keeps fixed inspectors inside the dynamic viewport", () => {
    for (const selector of [
      ".admin-shell",
      ".exhibition-inspector",
      ".submission-inspector",
    ]) {
      const declarations = declarationBlock(selector);
      expect(declarations).toMatch(/height:\s*100vh;\s*height:\s*100dvh/);
    }

    const dialogDeclarations = declarationBlock(".dialog");
    expect(dialogDeclarations).toMatch(
      /max-height:\s*min\(760px, calc\(100vh - 48px\)\);\s*max-height:\s*min\(760px, calc\(100dvh - 48px\)\)/,
    );
    const shortViewportRules = atRuleBlock(
      "@media (max-width: 1240px) and (max-height: 500px)",
    );
    expect(declarationBlock(".inspector-header", shortViewportRules)).toMatch(
      /max-height:\s*clamp\(64px, calc\(100dvh - 160px\), 180px\)/,
    );
    expect(declarationBlock(".inspector-content", shortViewportRules)).toMatch(
      /padding-block:\s*8px/,
    );
    expect(declarationBlock(".inspector-footer", shortViewportRules)).toMatch(
      /padding-bottom:\s*calc\(8px \+ env\(safe-area-inset-bottom, 0px\)\)/,
    );
  });

  it("reserves the iOS safe area below inspector actions", () => {
    expect(declarationBlock(".inspector-footer")).toMatch(
      /padding-bottom:\s*calc\(15px \+ env\(safe-area-inset-bottom, 0px\)\)/,
    );
    expect(indexHtml).toMatch(
      /<meta\s+name="viewport"\s+content="width=device-width, initial-scale=1\.0"\s*\/>/,
    );
    // Safari's default viewport fit keeps every edge inside the safe area.
    expect(indexHtml).not.toContain("viewport-fit=cover");
  });
});
