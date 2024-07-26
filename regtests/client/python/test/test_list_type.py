# coding: utf-8

"""
    Apache Iceberg REST Catalog API

    Defines the specification for the first version of the REST Catalog API. Implementations should ideally support both Iceberg table specs v1 and v2, with priority given to v2.

    The version of the OpenAPI document: 0.0.1
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


import unittest

from polaris.catalog.models.list_type import ListType

class TestListType(unittest.TestCase):
    """ListType unit test stubs"""

    def setUp(self):
        pass

    def tearDown(self):
        pass

    def make_instance(self, include_optional) -> ListType:
        """Test ListType
            include_option is a boolean, when False only required
            params are included, when True both required and
            optional params are included """
        # uncomment below to create an instance of `ListType`
        """
        model = ListType()
        if include_optional:
            return ListType(
                type = 'list',
                element_id = 56,
                element = None,
                element_required = True
            )
        else:
            return ListType(
                type = 'list',
                element_id = 56,
                element = None,
                element_required = True,
        )
        """

    def testListType(self):
        """Test ListType"""
        # inst_req_only = self.make_instance(include_optional=False)
        # inst_req_and_optional = self.make_instance(include_optional=True)

if __name__ == '__main__':
    unittest.main()
