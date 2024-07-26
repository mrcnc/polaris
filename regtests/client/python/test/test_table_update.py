# coding: utf-8

"""
    Apache Iceberg REST Catalog API

    Defines the specification for the first version of the REST Catalog API. Implementations should ideally support both Iceberg table specs v1 and v2, with priority given to v2.

    The version of the OpenAPI document: 0.0.1
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


import unittest

from polaris.catalog.models.table_update import TableUpdate

class TestTableUpdate(unittest.TestCase):
    """TableUpdate unit test stubs"""

    def setUp(self):
        pass

    def tearDown(self):
        pass

    def make_instance(self, include_optional) -> TableUpdate:
        """Test TableUpdate
            include_option is a boolean, when False only required
            params are included, when True both required and
            optional params are included """
        # uncomment below to create an instance of `TableUpdate`
        """
        model = TableUpdate()
        if include_optional:
            return TableUpdate(
                action = '',
                format_version = 56,
                var_schema = None,
                last_column_id = 56,
                schema_id = 56,
                spec = polaris.catalog.models.partition_spec.PartitionSpec(
                    spec_id = 56, 
                    fields = [
                        polaris.catalog.models.partition_field.PartitionField(
                            field_id = 56, 
                            source_id = 56, 
                            name = '', 
                            transform = '["identity","year","month","day","hour","bucket[256]","truncate[16]"]', )
                        ], ),
                spec_id = 56,
                sort_order = polaris.catalog.models.sort_order.SortOrder(
                    order_id = 56, 
                    fields = [
                        polaris.catalog.models.sort_field.SortField(
                            source_id = 56, 
                            transform = '["identity","year","month","day","hour","bucket[256]","truncate[16]"]', 
                            direction = 'asc', 
                            null_order = 'nulls-first', )
                        ], ),
                sort_order_id = 56,
                snapshot = polaris.catalog.models.snapshot.Snapshot(
                    snapshot_id = 56, 
                    parent_snapshot_id = 56, 
                    sequence_number = 56, 
                    timestamp_ms = 56, 
                    manifest_list = '', 
                    summary = {
                        'key' : ''
                        }, 
                    schema_id = 56, ),
                ref_name = '',
                type = 'tag',
                snapshot_id = 56,
                max_ref_age_ms = 56,
                max_snapshot_age_ms = 56,
                min_snapshots_to_keep = 56,
                snapshot_ids = [
                    56
                    ],
                location = '',
                updates = {
                    'key' : ''
                    },
                removals = [
                    ''
                    ],
                statistics = polaris.catalog.models.statistics_file.StatisticsFile(
                    snapshot_id = 56, 
                    statistics_path = '', 
                    file_size_in_bytes = 56, 
                    file_footer_size_in_bytes = 56, 
                    blob_metadata = [
                        polaris.catalog.models.blob_metadata.BlobMetadata(
                            type = '', 
                            snapshot_id = 56, 
                            sequence_number = 56, 
                            fields = [
                                56
                                ], 
                            properties = polaris.catalog.models.properties.properties(), )
                        ], )
            )
        else:
            return TableUpdate(
                action = '',
                format_version = 56,
                var_schema = None,
                schema_id = 56,
                spec = polaris.catalog.models.partition_spec.PartitionSpec(
                    spec_id = 56, 
                    fields = [
                        polaris.catalog.models.partition_field.PartitionField(
                            field_id = 56, 
                            source_id = 56, 
                            name = '', 
                            transform = '["identity","year","month","day","hour","bucket[256]","truncate[16]"]', )
                        ], ),
                spec_id = 56,
                sort_order = polaris.catalog.models.sort_order.SortOrder(
                    order_id = 56, 
                    fields = [
                        polaris.catalog.models.sort_field.SortField(
                            source_id = 56, 
                            transform = '["identity","year","month","day","hour","bucket[256]","truncate[16]"]', 
                            direction = 'asc', 
                            null_order = 'nulls-first', )
                        ], ),
                sort_order_id = 56,
                snapshot = polaris.catalog.models.snapshot.Snapshot(
                    snapshot_id = 56, 
                    parent_snapshot_id = 56, 
                    sequence_number = 56, 
                    timestamp_ms = 56, 
                    manifest_list = '', 
                    summary = {
                        'key' : ''
                        }, 
                    schema_id = 56, ),
                ref_name = '',
                type = 'tag',
                snapshot_id = 56,
                snapshot_ids = [
                    56
                    ],
                location = '',
                updates = {
                    'key' : ''
                    },
                removals = [
                    ''
                    ],
                statistics = polaris.catalog.models.statistics_file.StatisticsFile(
                    snapshot_id = 56, 
                    statistics_path = '', 
                    file_size_in_bytes = 56, 
                    file_footer_size_in_bytes = 56, 
                    blob_metadata = [
                        polaris.catalog.models.blob_metadata.BlobMetadata(
                            type = '', 
                            snapshot_id = 56, 
                            sequence_number = 56, 
                            fields = [
                                56
                                ], 
                            properties = polaris.catalog.models.properties.properties(), )
                        ], ),
        )
        """

    def testTableUpdate(self):
        """Test TableUpdate"""
        # inst_req_only = self.make_instance(include_optional=False)
        # inst_req_and_optional = self.make_instance(include_optional=True)

if __name__ == '__main__':
    unittest.main()
