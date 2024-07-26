# coding: utf-8

"""
    Apache Iceberg REST Catalog API

    Defines the specification for the first version of the REST Catalog API. Implementations should ideally support both Iceberg table specs v1 and v2, with priority given to v2.

    The version of the OpenAPI document: 0.0.1
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


import unittest

from polaris.catalog.models.snapshot_summary import SnapshotSummary

class TestSnapshotSummary(unittest.TestCase):
    """SnapshotSummary unit test stubs"""

    def setUp(self):
        pass

    def tearDown(self):
        pass

    def make_instance(self, include_optional) -> SnapshotSummary:
        """Test SnapshotSummary
            include_option is a boolean, when False only required
            params are included, when True both required and
            optional params are included """
        # uncomment below to create an instance of `SnapshotSummary`
        """
        model = SnapshotSummary()
        if include_optional:
            return SnapshotSummary(
                operation = 'append'
            )
        else:
            return SnapshotSummary(
                operation = 'append',
        )
        """

    def testSnapshotSummary(self):
        """Test SnapshotSummary"""
        # inst_req_only = self.make_instance(include_optional=False)
        # inst_req_and_optional = self.make_instance(include_optional=True)

if __name__ == '__main__':
    unittest.main()
