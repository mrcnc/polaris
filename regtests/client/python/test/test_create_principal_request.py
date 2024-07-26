# coding: utf-8

"""
    Polaris Management Service

    Defines the management APIs for using Polaris to create and manage Iceberg catalogs and their principals

    The version of the OpenAPI document: 0.0.1
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


import unittest

from polaris.management.models.create_principal_request import CreatePrincipalRequest

class TestCreatePrincipalRequest(unittest.TestCase):
    """CreatePrincipalRequest unit test stubs"""

    def setUp(self):
        pass

    def tearDown(self):
        pass

    def make_instance(self, include_optional) -> CreatePrincipalRequest:
        """Test CreatePrincipalRequest
            include_option is a boolean, when False only required
            params are included, when True both required and
            optional params are included """
        # uncomment below to create an instance of `CreatePrincipalRequest`
        """
        model = CreatePrincipalRequest()
        if include_optional:
            return CreatePrincipalRequest(
                principal = polaris.management.models.principal.Principal(
                    type = 'SERVICE', 
                    name = '', 
                    client_id = '', 
                    properties = {
                        'key' : ''
                        }, 
                    create_timestamp = 56, 
                    last_update_timestamp = 56, 
                    entity_version = 56, )
            )
        else:
            return CreatePrincipalRequest(
        )
        """

    def testCreatePrincipalRequest(self):
        """Test CreatePrincipalRequest"""
        # inst_req_only = self.make_instance(include_optional=False)
        # inst_req_and_optional = self.make_instance(include_optional=True)

if __name__ == '__main__':
    unittest.main()
