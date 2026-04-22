Behaviour.specify('input[name="_.oneShot"]', 'GitHubActionsAgentTemplate-oneShot', 0, function(cb) {
    function toggleIdle(checkbox) {
        var formItem = checkbox.closest('.jenkins-form-item');
        if (!formItem) return;
        var next = formItem.nextElementSibling;
        while (next && !next.querySelector('input[name="_.idleMinutes"]')) {
            next = next.nextElementSibling;
        }
        if (!next) return;
        var idleInput = next.querySelector('input[name="_.idleMinutes"]');
        if (idleInput) {
            idleInput.disabled = checkbox.checked;
        }
        next.style.opacity = checkbox.checked ? '0.4' : '1';
        next.style.pointerEvents = checkbox.checked ? 'none' : '';
    }

    toggleIdle(cb);
    cb.addEventListener('change', function() { toggleIdle(cb); });
    cb.addEventListener('click', function() { setTimeout(function(){ toggleIdle(cb); }, 0); });
});
