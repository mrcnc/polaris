# coding: utf-8

"""
    Apache Iceberg REST Catalog API

    Defines the specification for the first version of the REST Catalog API. Implementations should ideally support both Iceberg table specs v1 and v2, with priority given to v2.

    The version of the OpenAPI document: 0.0.1
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


import unittest

from polaris.catalog.models.view_version import ViewVersion

class TestViewVersion(unittest.TestCase):
    """ViewVersion unit test stubs"""

    def setUp(self):
        pass

    def tearDown(self):
        pass

    def make_instance(self, include_optional) -> ViewVersion:
        """Test ViewVersion
            include_option is a boolean, when False only required
            params are included, when True both required and
            optional params are included """
        # uncomment below to create an instance of `ViewVersion`
        """
        model = ViewVersion()
        if include_optional:
            return ViewVersion(
                version_id = 56,
                timestamp_ms = 56,
                schema_id = 56,
                summary = {
                    'key' : ''
                    },
                representations = [
                    null
                    ],
                default_catalog = '',
                default_namespace = ["accounting","tax"]
            )
        else:
            return ViewVersion(
                version_id = 56,
                timestamp_ms = 56,
                schema_id = 56,
                summary = {
                    'key' : ''
                    },
                representations = [
                    null
                    ],
                default_namespace = ["accounting","tax"],
        )
        """

    def testViewVersion(self):
        """Test ViewVersion"""
        # inst_req_only = self.make_instance(include_optional=False)
        # inst_req_and_optional = self.make_instance(include_optional=True)

if __name__ == '__main__':
    unittest.main()
