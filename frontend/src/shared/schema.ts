import { z } from "zod";
export const idSchema = z
  .number()
  .int()
  .positive()
  .refine(Number.isSafeInteger)
  .transform(String);
export const countSchema = z
  .number()
  .int()
  .nonnegative()
  .refine(Number.isSafeInteger);
export const instantSchema = z.iso.datetime({ offset: true });
export function validRouteId(id: string) {
  return /^[1-9]\d*$/.test(id) && Number.isSafeInteger(Number(id));
}
