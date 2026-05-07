// 11ty pagination — generates one HTML file per exhibition.
// Reads from _data/exhibitions.json (written by fetch-exhibitions.js).
// Output: /exhibitions/[slug]/index.html
//
// This is a scaffold; Task 18 replaces the inline render with the full
// detail template via includes.

module.exports = {
  data: {
    layout: "base.html",
    pagination: {
      data: "exhibitions.exhibitions",
      size: 1,
      alias: "exhibition",
    },
    eleventyComputed: {
      title: (data) => `${data.exhibition.name_ko} — gallr`,
    },
    permalink: (data) => `/exhibitions/${data.exhibition.slug}/index.html`,
  },
  render: function (data) {
    const ex = data.exhibition;
    return `<article class="detail-page">
      <h1>${this.escapeHtml(ex.name_ko)}</h1>
      <p>Status: ${ex.status}</p>
      <p>Slug: ${ex.slug}</p>
    </article>`;
  },
  escapeHtml: function (s) {
    return String(s).replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  },
};
