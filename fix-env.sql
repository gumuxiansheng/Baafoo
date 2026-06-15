UPDATE rules SET environments_json = '["staging-a"]' WHERE id IN ('staging-a-http','staging-a-kafka');
UPDATE rules SET environments_json = '["staging-b"]' WHERE id = 'staging-b-http';
