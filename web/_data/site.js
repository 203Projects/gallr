const DEFAULT_GALLERY_WORKSPACE_URL = "https://gallery.gallermap.com/";

function galleryWorkspaceUrl() {
  const configured = process.env.GALLR_GALLERY_WORKSPACE_URL?.trim();
  if (!configured) return DEFAULT_GALLERY_WORKSPACE_URL;

  let url;
  try {
    url = new URL(configured);
  } catch {
    throw new Error("GALLR_GALLERY_WORKSPACE_URL must be a valid URL");
  }

  if (url.protocol !== "https:" && url.protocol !== "http:") {
    throw new Error("GALLR_GALLERY_WORKSPACE_URL must use HTTP or HTTPS");
  }

  return url.toString();
}

module.exports = {
  url: "https://gallrmap.com",
  liveCountLabel: "1,200+",
  city: "Seoul",
  cityKo: "서울",
  appStoreUrl:
    "https://apps.apple.com/kr/app/gallr-%EA%B0%A4%EB%9F%AC-%EC%A0%84%EC%8B%9C-%EC%A0%95%EB%B3%B4/id6760855059",
  googlePlayUrl:
    "https://play.google.com/store/apps/details?id=com.gallr.app",
  galleryWorkspaceUrl: galleryWorkspaceUrl(),
  naverClientId: "dkd2c8bh63",
};
