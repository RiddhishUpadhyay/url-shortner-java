// API Base URL (Relative to hosted path)
const API_URL = '';

let currentUser = localStorage.getItem('username');
let authToken = localStorage.getItem('token');
let currentAuthTab = 'login';

// Initialize Page
document.addEventListener('DOMContentLoaded', () => {
    updateAppState();
    
    // Check if token exists to load URLs
    if (authToken) {
        fetchMyUrls();
    }
});

function updateAppState() {
    const userNavArea = document.getElementById('user-nav-area');
    const shortenSection = document.getElementById('shorten-section');
    const linksSection = document.getElementById('links-section');
    const unauthCallout = document.getElementById('unauth-callout');

    if (authToken && currentUser) {
        // Logged In
        userNavArea.innerHTML = `
            <span style="font-size: 14px; font-weight: 600; color: var(--accent-blue);">👋 Hello, ${currentUser}</span>
            <button class="btn btn-secondary" onclick="handleLogout()">Log Out</button>
        `;
        shortenSection.style.display = 'block';
        linksSection.style.display = 'block';
        unauthCallout.style.display = 'none';
    } else {
        // Logged Out
        userNavArea.innerHTML = `
            <button class="btn btn-secondary" onclick="openAuthModal('login')">Log In</button>
            <button class="btn btn-primary" onclick="openAuthModal('register')">Sign Up</button>
        `;
        shortenSection.style.display = 'none';
        linksSection.style.display = 'none';
        unauthCallout.style.display = 'block';
    }
}

// Modal Toggle Logic
function openAuthModal(tab = 'login') {
    const modal = document.getElementById('auth-modal');
    modal.style.display = 'flex';
    switchAuthTab(tab);
}

function closeAuthModal() {
    document.getElementById('auth-modal').style.display = 'none';
    document.getElementById('auth-username').value = '';
    document.getElementById('auth-password').value = '';
}

function switchAuthTab(tab) {
    currentAuthTab = tab;
    const tabLogin = document.getElementById('tab-login');
    const tabRegister = document.getElementById('tab-register');
    const authTitle = document.getElementById('auth-title');
    const submitBtn = document.getElementById('auth-submit-btn');

    if (tab === 'login') {
        tabLogin.classList.add('active');
        tabRegister.classList.remove('active');
        authTitle.innerText = 'Log In to SnipLink';
        submitBtn.innerText = 'Log In';
    } else {
        tabLogin.classList.remove('active');
        tabRegister.classList.add('active');
        authTitle.innerText = 'Create your Account';
        submitBtn.innerText = 'Sign Up';
    }
}

// Advanced Options Toggle
function toggleAdvancedOptions() {
    const panel = document.getElementById('advanced-options-panel');
    if (panel.style.display === 'grid') {
        panel.style.display = 'none';
    } else {
        panel.style.display = 'grid';
    }
}

// Auth API call
async function handleAuthSubmit(event) {
    event.preventDefault();
    const usernameVal = document.getElementById('auth-username').value.trim();
    const passwordVal = document.getElementById('auth-password').value;

    if (!usernameVal || !passwordVal) {
        showToast('Username and password are required.', 'error');
        return;
    }

    const endpoint = currentAuthTab === 'login' ? '/api/auth/login' : '/api/auth/register';

    try {
        const response = await fetch(API_URL + endpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: usernameVal, password: passwordVal })
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || 'Authentication failed');
        }

        const data = await response.json();

        if (currentAuthTab === 'login') {
            localStorage.setItem('token', data.token);
            localStorage.setItem('username', data.username);
            authToken = data.token;
            currentUser = data.username;
            
            showToast('Successfully logged in!', 'success');
            closeAuthModal();
            updateAppState();
            fetchMyUrls();
        } else {
            showToast('Registration successful! Please log in.', 'success');
            switchAuthTab('login');
            document.getElementById('auth-password').value = '';
        }
    } catch (err) {
        showToast(err.message, 'error');
    }
}

function handleLogout() {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    authToken = null;
    currentUser = null;
    updateAppState();
    showToast('Logged out successfully.', 'success');
}

// Shorten URL Logic
async function handleShorten(event) {
    event.preventDefault();
    const originalUrlInput = document.getElementById('originalUrl');
    const customAliasInput = document.getElementById('customAlias');
    const expiresInDaysInput = document.getElementById('expiresInDays');

    const originalUrl = originalUrlInput.value.trim();
    const customAlias = customAliasInput.value.trim() || null;
    const expiresInDays = parseInt(expiresInDaysInput.value) || null;

    if (!originalUrl) {
        showToast('Please enter a URL.', 'error');
        return;
    }

    try {
        const response = await fetch(API_URL + '/api/urls/shorten', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${authToken}`
            },
            body: JSON.stringify({ originalUrl, customAlias, expiresInDays })
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || 'Failed to shorten link');
        }

        const data = await response.json();

        showToast('Link shortened successfully!', 'success');
        originalUrlInput.value = '';
        customAliasInput.value = '';
        expiresInDaysInput.value = '';
        
        // Hide advanced options after submit
        document.getElementById('advanced-options-panel').style.display = 'none';

        fetchMyUrls();
    } catch (err) {
        showToast(err.message, 'error');
    }
}

// Fetch Active Links List
async function fetchMyUrls() {
    const container = document.getElementById('links-container');
    
    try {
        const response = await fetch(API_URL + '/api/urls/my-urls', {
            headers: { 'Authorization': `Bearer ${authToken}` }
        });

        if (!response.ok) {
            if (response.status === 410 || response.status === 401) {
                handleLogout();
                return;
            }
            throw new Error('Failed to fetch your links');
        }

        const urls = await response.json();
        
        if (urls.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">🔗</div>
                    <p>No shortened links yet. Shorten your first link above!</p>
                </div>
            `;
            return;
        }

        container.innerHTML = urls.map(u => {
            const dateStr = new Date(u.createdAt).toLocaleDateString();
            const expStr = u.expiresAt ? `Exp: ${new Date(u.expiresAt).toLocaleDateString()}` : 'No Expiry';
            
            return `
                <div class="glass-panel url-card" id="url-card-${u.id}">
                    <div class="url-info">
                        <a href="${u.shortUrl}" target="_blank" class="shortened-link">${u.shortUrl}</a>
                        <div class="original-link">${u.originalUrl}</div>
                        <div class="url-meta">
                            <span>📅 Created: ${dateStr}</span>
                            <span>⏳ ${expStr}</span>
                        </div>
                    </div>
                    <div class="url-actions">
                        <span class="clicks-badge">📊 ${u.clicksCount} clicks</span>
                        <a href="/dashboard.html?code=${u.shortCode}" class="btn btn-secondary btn-sm">Dashboard</a>
                        <button class="btn btn-primary btn-sm" onclick="copyText('${u.shortUrl}')">Copy</button>
                        <button class="btn btn-danger btn-sm" onclick="deleteUrl(${u.id})">Delete</button>
                    </div>
                </div>
            `;
        }).join('');
    } catch (err) {
        showToast(err.message, 'error');
    }
}

// Delete Link
async function deleteUrl(id) {
    if (!confirm('Are you sure you want to delete this link? It will delete all tracking analytics as well.')) {
        return;
    }

    try {
        const response = await fetch(`${API_URL}/api/urls/${id}`, {
            method: 'DELETE',
            headers: { 'Authorization': `Bearer ${authToken}` }
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(errorText || 'Failed to delete URL');
        }

        showToast('Link deleted successfully.', 'success');
        
        // Remove from list view smoothly
        const element = document.getElementById(`url-card-${id}`);
        if (element) {
            element.style.opacity = '0';
            element.style.transform = 'translateY(10px)';
            setTimeout(() => {
                element.remove();
                if (document.getElementById('links-container').children.length === 0) {
                    fetchMyUrls();
                }
            }, 200);
        }
    } catch (err) {
        showToast(err.message, 'error');
    }
}

// Global Helpers
function copyText(text) {
    navigator.clipboard.writeText(text).then(() => {
        showToast('Copied to clipboard!', 'success');
    }).catch(() => {
        showToast('Failed to copy text.', 'error');
    });
}

function showToast(message, type = 'success') {
    const toast = document.getElementById('toast');
    const msgSpan = document.getElementById('toast-message');
    const iconSpan = document.getElementById('toast-icon');

    toast.className = `toast toast-${type} show`;
    msgSpan.innerText = message;
    iconSpan.innerText = type === 'success' ? '✅' : '❌';

    setTimeout(() => {
        toast.classList.remove('show');
    }, 3000);
}
