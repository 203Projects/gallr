const esbuild = require("esbuild");
esbuild.buildSync({
  entryPoints: ["registration/client.js"],
  bundle: true,
  minify: true,
  platform: "browser",
  target: ["es2022"],
  outfile: "public/build/registration.js",
  legalComments: "eof",
});
