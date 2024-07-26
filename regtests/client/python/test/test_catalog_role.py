# coding: utf-8

"""
    Polaris Management Service

    Defines the management APIs for using Polaris to create and manage Iceberg catalogs and their principals

    The version of the OpenAPI document: 0.0.1
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


import unittest

from polaris.management.models.catalog_role import CatalogRole

class TestCatalogRole(unittest.TestCase):
    """CatalogRole unit test stubs"""

    def setUp(self):
        pass

    def tearDown(self):
        pass

    def make_instance(self, include_optional) -> CatalogRole:
        """Test CatalogRole
            include_option is a boolean, when False only required
            params are included, when True both required and
            optional params are included """
        # uncomment below to create an instance of `CatalogRole`
        """
        model = CatalogRole()
        if include_optional:
            return CatalogRole(
                name = '',
                properties = {
                    'key' : ''
                    },
                create_timestamp = 56,
                last_update_timestamp = 56,
                entity_version = 56
            )
        else:
            return CatalogRole(
                name = '',
        )
        """

    def testCatalogRole(self):
        """Test CatalogRole"""
        # inst_req_only = self.make_instance(include_optional=False)
        # inst_req_and_optional = self.make_instance(include_optional=True)

if __name__ == '__main__':
    unittest.main()
