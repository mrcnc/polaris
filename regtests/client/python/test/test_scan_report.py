# coding: utf-8

"""
    Apache Iceberg REST Catalog API

    Defines the specification for the first version of the REST Catalog API. Implementations should ideally support both Iceberg table specs v1 and v2, with priority given to v2.

    The version of the OpenAPI document: 0.0.1
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


import unittest

from polaris.catalog.models.scan_report import ScanReport

class TestScanReport(unittest.TestCase):
    """ScanReport unit test stubs"""

    def setUp(self):
        pass

    def tearDown(self):
        pass

    def make_instance(self, include_optional) -> ScanReport:
        """Test ScanReport
            include_option is a boolean, when False only required
            params are included, when True both required and
            optional params are included """
        # uncomment below to create an instance of `ScanReport`
        """
        model = ScanReport()
        if include_optional:
            return ScanReport(
                table_name = '',
                snapshot_id = 56,
                filter = None,
                schema_id = 56,
                projected_field_ids = [
                    56
                    ],
                projected_field_names = [
                    ''
                    ],
                metrics = {"metrics":{"total-planning-duration":{"count":1,"time-unit":"nanoseconds","total-duration":2644235116},"result-data-files":{"unit":"count","value":1},"result-delete-files":{"unit":"count","value":0},"total-data-manifests":{"unit":"count","value":1},"total-delete-manifests":{"unit":"count","value":0},"scanned-data-manifests":{"unit":"count","value":1},"skipped-data-manifests":{"unit":"count","value":0},"total-file-size-bytes":{"unit":"bytes","value":10},"total-delete-file-size-bytes":{"unit":"bytes","value":0}}},
                metadata = {
                    'key' : ''
                    }
            )
        else:
            return ScanReport(
                table_name = '',
                snapshot_id = 56,
                filter = None,
                schema_id = 56,
                projected_field_ids = [
                    56
                    ],
                projected_field_names = [
                    ''
                    ],
                metrics = {"metrics":{"total-planning-duration":{"count":1,"time-unit":"nanoseconds","total-duration":2644235116},"result-data-files":{"unit":"count","value":1},"result-delete-files":{"unit":"count","value":0},"total-data-manifests":{"unit":"count","value":1},"total-delete-manifests":{"unit":"count","value":0},"scanned-data-manifests":{"unit":"count","value":1},"skipped-data-manifests":{"unit":"count","value":0},"total-file-size-bytes":{"unit":"bytes","value":10},"total-delete-file-size-bytes":{"unit":"bytes","value":0}}},
        )
        """

    def testScanReport(self):
        """Test ScanReport"""
        # inst_req_only = self.make_instance(include_optional=False)
        # inst_req_and_optional = self.make_instance(include_optional=True)

if __name__ == '__main__':
    unittest.main()
