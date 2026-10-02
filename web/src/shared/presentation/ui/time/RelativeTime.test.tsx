import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ClockProvider } from "../../providers/ClockProvider";
import { RelativeTime } from "./RelativeTime";

const now = new Date("2026-10-02T12:00:00.000Z");

describe("RelativeTime", () => {
    it("renders relative text from the clock with the ISO dateTime", () => {
        render(
            <ClockProvider clock={{ now: () => now }}>
                <RelativeTime dateTime="2026-10-02T11:57:00Z" />
            </ClockProvider>,
        );

        const time = screen.getByText("3 minutes ago");
        expect(time.tagName).toBe("TIME");
        expect(time).toHaveAttribute("datetime", "2026-10-02T11:57:00.000Z");
        expect(time.getAttribute("title")).toMatch(/2026/);
    });

    it("prefers an explicit now over the clock", () => {
        render(<RelativeTime dateTime="2026-09-30T12:00:00Z" now={now} />);

        expect(screen.getByText("2 days ago")).toBeInTheDocument();
    });

    it("renders nothing for an invalid date", () => {
        const { container } = render(<RelativeTime dateTime="not a date" now={now} />);

        expect(container).toBeEmptyDOMElement();
    });
});
