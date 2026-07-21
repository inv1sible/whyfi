// leaflet.heat has no official type definitions; this augments the `L`
// namespace with the one function it adds at runtime.
import * as L from "leaflet";

declare module "leaflet" {
  function heatLayer(
    latlngs: Array<[number, number, number?]>,
    options?: { radius?: number; blur?: number; maxZoom?: number; max?: number; gradient?: Record<number, string> },
  ): L.Layer;
}
