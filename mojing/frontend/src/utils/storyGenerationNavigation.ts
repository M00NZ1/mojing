type RouteLocation = {
  pathname: string;
  search: string;
  hash: string;
};

export function shouldBlockStoryGenerationNavigation(
  isGenerating: boolean,
  allowNavigation: boolean,
  currentLocation: RouteLocation,
  nextLocation: RouteLocation,
): boolean {
  if (!isGenerating || allowNavigation) return false;
  return currentLocation.pathname !== nextLocation.pathname
    || currentLocation.search !== nextLocation.search
    || currentLocation.hash !== nextLocation.hash;
}
