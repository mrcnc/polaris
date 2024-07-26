# coding: utf-8

"""
    Apache Iceberg REST Catalog API

    Defines the specification for the first version of the REST Catalog API. Implementations should ideally support both Iceberg table specs v1 and v2, with priority given to v2.

    The version of the OpenAPI document: 0.0.1
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


from __future__ import annotations
import json
from enum import Enum
from typing_extensions import Self


class FileFormat(str, Enum):
    """
    FileFormat
    """

    """
    allowed enum values
    """
    AVRO = 'avro'
    ORC = 'orc'
    PARQUET = 'parquet'

    @classmethod
    def from_json(cls, json_str: str) -> Self:
        """Create an instance of FileFormat from a JSON string"""
        return cls(json.loads(json_str))


