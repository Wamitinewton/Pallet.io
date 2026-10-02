import type { ComponentProps } from "react";
import { ICON_PATHS, type IconName } from "./paths";

export type { IconName } from "./paths";

export type IconProps = Omit<ComponentProps<"svg">, "children"> & {
    name: IconName;
    size?: number;
    label?: string;
};

export function Icon({ name, size = 16, label, ...props }: IconProps) {
    const a11y = label ? { role: "img", "aria-label": label } : { "aria-hidden": true };
    return (
        <svg
            viewBox="0 0 24 24"
            width={size}
            height={size}
            fill="none"
            stroke="currentColor"
            strokeWidth={1.8}
            strokeLinecap="round"
            strokeLinejoin="round"
            focusable="false"
            {...a11y}
            {...props}
        >
            {ICON_PATHS[name].map((shape, index) => {
                switch (shape.kind) {
                    case "path":
                        return <path key={index} d={shape.d} />;
                    case "circle":
                        return <circle key={index} cx={shape.cx} cy={shape.cy} r={shape.r} />;
                    case "rect":
                        return (
                            <rect
                                key={index}
                                x={shape.x}
                                y={shape.y}
                                width={shape.width}
                                height={shape.height}
                                rx={"rx" in shape ? shape.rx : undefined}
                            />
                        );
                }
            })}
        </svg>
    );
}
