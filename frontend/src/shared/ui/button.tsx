// Local shadcn-style Button: native button, or Radix Slot for links.
import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../utils";
export const buttonVariants = cva("btn", {
  variants: {
    variant: {
      primary: "btn--primary",
      secondary: "btn--secondary",
      ghost: "btn--ghost",
      danger: "btn--danger",
    },
    size: { md: "", sm: "btn--sm" },
    icon: { true: "btn--icon", false: "" },
  },
  defaultVariants: { variant: "primary", size: "md", icon: false },
});
export function Button({
  className,
  variant,
  size,
  icon,
  asChild = false,
  type,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & { asChild?: boolean }) {
  const Comp = asChild ? Slot : "button";
  return (
    <Comp
      className={cn(buttonVariants({ variant, size, icon }), className)}
      // Native buttons default to "button" so they never submit by accident.
      type={asChild ? type : (type ?? "button")}
      {...props}
    />
  );
}
