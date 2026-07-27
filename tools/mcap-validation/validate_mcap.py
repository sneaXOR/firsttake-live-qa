"""Validate FirstTake's Kotlin MCAP output with the official Python reader."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

import mcap
from mcap.reader import make_reader


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mcap_path", type=Path)
    parser.add_argument("--expect-messages", type=int)
    args = parser.parse_args()

    topics: Counter[str] = Counter()
    schemas: set[tuple[int, str, str]] = set()
    sequence_by_topic: dict[str, list[int]] = {}
    decoded_payloads = 0

    with args.mcap_path.open("rb") as stream:
        reader = make_reader(stream)
        header = reader.get_header()
        for schema, channel, message in reader.iter_messages(
            log_time_order=False,
        ):
            topics[channel.topic] += 1
            sequence_by_topic.setdefault(channel.topic, []).append(
                message.sequence,
            )
            if schema is not None:
                schemas.add((schema.id, schema.name, schema.encoding))
            json.loads(message.data)
            decoded_payloads += 1

    if args.expect_messages is not None:
        if decoded_payloads != args.expect_messages:
            raise SystemExit(
                f"expected {args.expect_messages} messages, "
                f"read {decoded_payloads}",
            )

    required_topics = {
        "/firsttake/clock_anchor",
        "/imu/accelerometer",
        "/imu/gyroscope",
    }
    allowed_topics = required_topics | {
        "/firsttake/camera_analysis_frame",
        "/firsttake/camera_capture_result",
        "/firsttake/capture_event",
    }
    unexpected_topics = set(topics) - allowed_topics
    if unexpected_topics:
        raise SystemExit(
            f"unexpected topics: {sorted(unexpected_topics)}",
        )
    missing_topics = required_topics - set(topics)
    if missing_topics:
        raise SystemExit(
            f"missing required topics: {sorted(missing_topics)}",
        )

    for topic, sequences in sequence_by_topic.items():
        expected = list(range(len(sequences)))
        if sequences != expected:
            raise SystemExit(
                f"non-contiguous sequences on {topic}: {sequences}",
            )

    print(
        json.dumps(
            {
                "reader": f"mcap-python/{mcap.__version__}",
                "library": header.library,
                "profile": header.profile,
                "messages": decoded_payloads,
                "topics": dict(sorted(topics.items())),
                "schemas": [
                    {
                        "id": schema_id,
                        "name": name,
                        "encoding": encoding,
                    }
                    for schema_id, name, encoding in sorted(schemas)
                ],
                "status": "VALID",
            },
            indent=2,
            sort_keys=True,
        ),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
