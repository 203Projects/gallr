import { assertStrictEquals } from "@std/assert";
import { distanceMeters, orderedPair, positionKey } from "./position.ts";

Deno.test("position keys keep five decimals", () => {
  assertStrictEquals(positionKey(37.5, 127), "37.50000,127.00000");
  assertStrictEquals(positionKey(33.12, 124.6), "33.12000,124.60000");
});

Deno.test("position keys round as Postgres numeric does, from 15 significant digits", () => {
  // 37.200005 is stored as 37.2000049999…: toFixed(5) gives 37.20000, Postgres gives 37.20001.
  assertStrictEquals((37.200005).toFixed(5), "37.20000");
  assertStrictEquals(positionKey(37.200005, 127.000025), "37.20001,127.00003");
  assertStrictEquals(positionKey(37.544505, 127.056004), "37.54451,127.05600");
  assertStrictEquals(
    positionKey(37.5444949, 127.0560049),
    "37.54449,127.05600",
  );
  assertStrictEquals(positionKey(37.999995, 131.999995), "38.00000,132.00000");
});

Deno.test("pairs are ordered bytewise with the smaller key first", () => {
  const a = "37.54450,127.05600";
  const b = "37.53450,127.00100";
  assertStrictEquals(orderedPair(a, b).reversed, true);
  assertStrictEquals(orderedPair(a, b).fromKey, b);
  assertStrictEquals(orderedPair(b, a).reversed, false);
});

Deno.test("distance between nearby points is in metres", () => {
  const meters = distanceMeters({ lat: 37.5445, lng: 127.056 }, {
    lat: 37.5446,
    lng: 127.056,
  });
  assertStrictEquals(Math.round(meters), 11);
});
